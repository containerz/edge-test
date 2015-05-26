package ly.stealth.mesos.logging

import java.io.FileInputStream
import java.util.Properties

import _root_.io.confluent.kafka.serializers.{KafkaAvroDecoder, KafkaAvroSerializer}
import kafka.utils.VerifiableProperties
import org.apache.avro.generic.{GenericRecord, IndexedRecord}
import org.apache.kafka.clients.producer.ProducerConfig._
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.log4j.Logger
import org.codehaus.jackson.Version
import org.codehaus.jackson.map.module.SimpleModule
import org.codehaus.jackson.map.{DeserializationContext, KeyDeserializer, ObjectMapper}

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}

class Transform(config: ExecutorConfigBase) {
  final val CONTENT_TYPE_AVRO = "avro/binary"
  final val CONTENT_TYPE_PROTOBUF = "application/x-protobuf"
  final val CONTENT_TYPE_JSON = "application/json"

  private val logger = Logger.getLogger(this.getClass)
  private val mapper = new ObjectMapper()
  private val module = new SimpleModule("charsequence-module", Version.unknownVersion())
  module.addKeyDeserializer(classOf[CharSequence], new CharSequenceKeyDeserializer)
  mapper.registerModule(module)

  private val props = new Properties()
  props.load(new FileInputStream(config.producerConfig))
  props.put(KEY_SERIALIZER_CLASS_CONFIG, classOf[KafkaAvroSerializer])
  props.put(VALUE_SERIALIZER_CLASS_CONFIG, classOf[KafkaAvroSerializer])
  logger.info("Producer properties: " + props)

  private val producer = new KafkaProducer[Any, IndexedRecord](props)

  private val avroDecoder = new KafkaAvroDecoder(new VerifiableProperties(props))

  def transform(data: Array[Byte], contentType: String) {
    val received = timing("received")

    val logLineOpt = contentType match {
      case CONTENT_TYPE_JSON => this.handleJson(data)
      case CONTENT_TYPE_PROTOBUF => this.handleProtobuf(data)
      case CONTENT_TYPE_AVRO => this.handleAvro(data)
      case _ =>
        logger.warn(s"Content-Type $contentType is invalid")
        None
    }

    logLineOpt.foreach { logLine =>
      logLine.setSize(data.length.toLong)
      logLine.getTimings.add(received)
      logLine.getTimings.add(timing("sent"))

      producer.send(new ProducerRecord[Any, IndexedRecord](config.topic, logLine))
    }
  }

  private def handleJson(body: Array[Byte]): Option[LogLine] = {
    Try(mapper.readValue(body, classOf[LogLine])) match {
      case Success(logLine) => Some(logLine)
      case Failure(ex) =>
        logger.warn("", ex)
        None
    }
  }

  private def handleProtobuf(body: Array[Byte]): Option[LogLine] = {
    Try(proto.Logline.LogLine.parseFrom(body)) match {
      case Success(protoLine) =>
        val logLine = new LogLine()
        logLine.setLine(protoLine.getLine)
        logLine.setLogtypeid(protoLine.getLogtypeid)
        logLine.setSource(protoLine.getSource)
        logLine.setTag(mapAsJavaMap(protoLine.getTagList.map(tag => tag.getKey -> tag.getValue).toMap))
        logLine.setTimings(protoLine.getTimingsList.map(protoTiming => Timing.newBuilder().setEventName(protoTiming.getEventName).setValue(protoTiming.getValue).build))
        Some(logLine)
      case Failure(ex) =>
        logger.warn("", ex)
        None
    }
  }

  private def handleAvro(body: Array[Byte]): Option[LogLine] = {
    Try(avroDecoder.fromBytes(body)) match {
      case Success(obj) =>
        val generic = obj.asInstanceOf[GenericRecord]
        val logLine = new LogLine()
        logLine.setLine(generic.get("line").asInstanceOf[CharSequence])
        logLine.setLogtypeid(generic.get("logtypeid").asInstanceOf[java.lang.Long])
        logLine.setSource(generic.get("source").asInstanceOf[CharSequence])
        val tags = generic.get("tag")
        if (tags != null) logLine.setTag(tags.asInstanceOf[Map[CharSequence, CharSequence]])
        logLine.setTimings(generic.get("timings").asInstanceOf[java.util.List[GenericRecord]].map { timing =>
          Timing.newBuilder().setEventName(timing.get("eventName").asInstanceOf[CharSequence]).setValue(timing.get("value").asInstanceOf[java.lang.Long]).build
        })
        Some(logLine)
      case Failure(ex) =>
        logger.warn("", ex)
        None
    }
  }

  //TODO ntpstatus
  private def timing(name: String): Timing = Timing.newBuilder().setEventName(name).setValue(System.currentTimeMillis()).build
}

class CharSequenceKeyDeserializer extends KeyDeserializer {
  override def deserializeKey(key: String, ctxt: DeserializationContext): AnyRef = key
}
