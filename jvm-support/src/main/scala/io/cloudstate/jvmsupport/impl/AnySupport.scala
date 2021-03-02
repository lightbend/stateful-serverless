/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.jvmsupport.impl

import java.io.ByteArrayOutputStream
import java.util.Locale

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.base.CaseFormat
import com.google.protobuf.{
  ByteString,
  CodedInputStream,
  CodedOutputStream,
  Descriptors,
  Parser,
  UnsafeByteOperations,
  WireFormat,
  Any => JavaPbAny
}
import com.google.protobuf.any.{Any => ScalaPbAny}
import io.cloudstate.jvmsupport.Jsonable
import io.cloudstate.jvmsupport.impl.AnySupport.Prefer.{Java, Scala}
import org.slf4j.LoggerFactory
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import scalapb.options.Scalapb

import scala.collection.concurrent.TrieMap
import scala.reflect.ClassTag
import scala.util.Try
import scala.collection.JavaConverters._

object AnySupport {

  private final val CloudStatePrimitiveFieldNumber = 1
  final val CloudStatePrimitive = "p.cloudstate.io/"
  final val CloudStateJson = "json.cloudstate.io/"
  final val DefaultTypeUrlPrefix = "type.googleapis.com"

  private sealed abstract class Primitive[T: ClassTag] {
    val name = fieldType.name().toLowerCase(Locale.ROOT)
    val fullName = CloudStatePrimitive + name
    final val clazz = implicitly[ClassTag[T]].runtimeClass
    def write(stream: CodedOutputStream, t: T): Unit
    def read(stream: CodedInputStream): T
    def fieldType: WireFormat.FieldType
    def defaultValue: T
    val tag = (CloudStatePrimitiveFieldNumber << 3) | fieldType.getWireType
  }

  private final object StringPrimitive extends Primitive[String] {
    override def fieldType = WireFormat.FieldType.STRING
    override def defaultValue = ""
    override def write(stream: CodedOutputStream, t: String) = stream.writeString(CloudStatePrimitiveFieldNumber, t)
    override def read(stream: CodedInputStream) = stream.readString()
  }
  private final object BytesPrimitive extends Primitive[ByteString] {
    override def fieldType = WireFormat.FieldType.BYTES
    override def defaultValue = ByteString.EMPTY
    override def write(stream: CodedOutputStream, t: ByteString) = stream.writeBytes(CloudStatePrimitiveFieldNumber, t)
    override def read(stream: CodedInputStream) = stream.readBytes()
  }

  private final val Primitives = Seq(
    StringPrimitive,
    BytesPrimitive,
    new Primitive[Integer] {
      override def fieldType = WireFormat.FieldType.INT32
      override def defaultValue = 0
      override def write(stream: CodedOutputStream, t: Integer) = stream.writeInt32(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readInt32()
    },
    new Primitive[java.lang.Long] {
      override def fieldType = WireFormat.FieldType.INT64
      override def defaultValue = 0L
      override def write(stream: CodedOutputStream, t: java.lang.Long) =
        stream.writeInt64(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readInt64()
    },
    new Primitive[java.lang.Float] {
      override def fieldType = WireFormat.FieldType.FLOAT
      override def defaultValue = 0f
      override def write(stream: CodedOutputStream, t: java.lang.Float) =
        stream.writeFloat(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readFloat()
    },
    new Primitive[java.lang.Double] {
      override def fieldType = WireFormat.FieldType.DOUBLE
      override def defaultValue = 0d
      override def write(stream: CodedOutputStream, t: java.lang.Double) =
        stream.writeDouble(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readDouble()
    },
    new Primitive[java.lang.Boolean] {
      override def fieldType = WireFormat.FieldType.BOOL
      override def defaultValue = false
      override def write(stream: CodedOutputStream, t: java.lang.Boolean) =
        stream.writeBool(CloudStatePrimitiveFieldNumber, t)
      override def read(stream: CodedInputStream) = stream.readBool()
    }
  )

  private final val ClassToPrimitives = Primitives
    .map(p => p.clazz -> p)
    .asInstanceOf[Seq[(Any, Primitive[Any])]]
    .toMap
  private final val NameToPrimitives = Primitives
    .map(p => p.fullName -> p)
    .asInstanceOf[Seq[(String, Primitive[Any])]]
    .toMap

  private final val objectMapper = new ObjectMapper()

  private def primitiveToBytes[T](primitive: Primitive[T], value: T): ByteString =
    if (value != primitive.defaultValue) {
      val baos = new ByteArrayOutputStream()
      val stream = CodedOutputStream.newInstance(baos)
      primitive.write(stream, value)
      stream.flush()
      UnsafeByteOperations.unsafeWrap(baos.toByteArray)
    } else ByteString.EMPTY

  private def bytesToPrimitive[T](primitive: Primitive[T], bytes: ByteString) = {
    val stream = bytes.newCodedInput()
    if (Stream
          .continually(stream.readTag())
          .takeWhile(_ != 0)
          .exists { tag =>
            if (primitive.tag != tag) {
              stream.skipField(tag)
              false
            } else true
          }) {
      primitive.read(stream)
    } else primitive.defaultValue
  }

  /**
   * When locating protobufs, if both a Java and a ScalaPB generated class is found on the classpath, this says which
   * should be preferred.
   */
  sealed trait Prefer
  final object Prefer {
    case object Java extends Prefer
    case object Scala extends Prefer
  }

  final val PREFER_JAVA = Java
  final val PREFER_SCALA = Scala

  def flattenDescriptors(descriptors: Seq[Descriptors.FileDescriptor]): Map[String, Descriptors.FileDescriptor] =
    flattenDescriptors(Map.empty, descriptors)

  private def flattenDescriptors(
      seenSoFar: Map[String, Descriptors.FileDescriptor],
      descriptors: Seq[Descriptors.FileDescriptor]
  ): Map[String, Descriptors.FileDescriptor] =
    descriptors.foldLeft(seenSoFar) {
      case (results, descriptor) =>
        val descriptorName = descriptor.getName
        if (results.contains(descriptorName)) results
        else {
          val withDesc = results.updated(descriptorName, descriptor)
          flattenDescriptors(withDesc,
                             descriptor.getDependencies.asScala.toSeq ++ descriptor.getPublicDependencies.asScala)
        }
    }
}

class AnySupport(descriptors: Array[Descriptors.FileDescriptor],
                 classLoader: ClassLoader,
                 typeUrlPrefix: String = AnySupport.DefaultTypeUrlPrefix,
                 prefer: AnySupport.Prefer = AnySupport.Prefer.Java) {
  import AnySupport._
  private val allDescriptors = flattenDescriptors(descriptors)

  private final val log = LoggerFactory.getLogger(getClass)

  private val allTypes = (for {
    descriptor <- allDescriptors.values
    messageType <- descriptor.getMessageTypes.asScala
  } yield messageType.getFullName -> messageType).toMap

  private val reflectionCache = TrieMap.empty[String, Try[ResolvedType[Any]]]

  private def strippedFileName(fileName: String) =
    fileName.split(Array('/', '\\')).last.stripSuffix(".proto")

  private def tryResolveJavaPbType(typeDescriptor: Descriptors.Descriptor) = {
    val fileDescriptor = typeDescriptor.getFile
    val options = fileDescriptor.getOptions
    // Firstly, determine the java package
    val packageName =
      if (options.hasJavaPackage) options.getJavaPackage + "."
      else if (fileDescriptor.getPackage.nonEmpty) fileDescriptor.getPackage + "."
      else ""

    val outerClassName =
      if (options.hasJavaMultipleFiles && options.getJavaMultipleFiles) ""
      else if (options.hasJavaOuterClassname) options.getJavaOuterClassname + "$"
      else if (fileDescriptor.getName.nonEmpty)
        CaseFormat.LOWER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, strippedFileName(fileDescriptor.getName)) + "$"
      else ""

    val className = packageName + outerClassName + typeDescriptor.getName
    try {
      log.debug("Attempting to load com.google.protobuf.Message class {}", className)
      val clazz = classLoader.loadClass(className)
      if (classOf[com.google.protobuf.Message].isAssignableFrom(clazz)) {
        val parser = clazz.getMethod("parser").invoke(null).asInstanceOf[Parser[com.google.protobuf.Message]]
        Some(
          new JavaPbResolvedType(clazz.asInstanceOf[Class[com.google.protobuf.Message]],
                                 typeUrlPrefix + "/" + typeDescriptor.getFullName,
                                 parser)
        )
      } else {
        None
      }
    } catch {
      case cnfe: ClassNotFoundException =>
        log.debug("Failed to load class", cnfe)
        None
      case nsme: NoSuchElementException =>
        throw SerializationException(
          s"Found com.google.protobuf.Message class $className to deserialize protobuf ${typeDescriptor.getFullName} but it didn't have a static parser() method on it.",
          nsme
        )
      case iae @ (_: IllegalAccessException | _: IllegalArgumentException) =>
        throw SerializationException(s"Could not invoke $className.parser()", iae)
      case cce: ClassCastException =>
        throw SerializationException(s"$className.parser() did not return a ${classOf[Parser[_]]}", cce)
    }
  }

  private def tryResolveScalaPbType(typeDescriptor: Descriptors.Descriptor) = {
    // todo - attempt to load the package.proto file for this package to get default options from there
    val fileDescriptor = typeDescriptor.getFile
    val options = fileDescriptor.getOptions
    val scalaOptions: Scalapb.ScalaPbOptions = if (options.hasExtension(Scalapb.options)) {
      options.getExtension(Scalapb.options)
    } else Scalapb.ScalaPbOptions.getDefaultInstance

    // Firstly, determine the java package
    val packageName =
      if (scalaOptions.hasPackageName) scalaOptions.getPackageName + "."
      else if (options.hasJavaPackage) options.getJavaPackage + "."
      else if (fileDescriptor.getPackage.nonEmpty) fileDescriptor.getPackage + "."
      else ""

    // flat package could be overridden on the command line, so attempt to load both possibilities if it's not
    // explicitly setclassLoader.loadClass(className)
    val possibleBaseNames =
      if (scalaOptions.hasFlatPackage) {
        if (scalaOptions.getFlatPackage) Seq("")
        else Seq(fileDescriptor.getName.stripSuffix(".proto") + ".")
      } else if (fileDescriptor.getName.nonEmpty) Seq("", strippedFileName(fileDescriptor.getName) + ".")
      else Seq("")

    possibleBaseNames.collectFirst(Function.unlift { baseName =>
      val className = packageName + baseName + typeDescriptor.getName
      val companionName = className + "$"
      try {
        log.debug("Attempting to load scalapb.GeneratedMessageCompanion object {}", className)
        val clazz = classLoader.loadClass(className)
        val companion = classLoader.loadClass(companionName)
        if (classOf[GeneratedMessageCompanion[_]].isAssignableFrom(companion) &&
            classOf[scalapb.GeneratedMessage].isAssignableFrom(clazz)) {
          val companionObject = companion.getField("MODULE$").get(null).asInstanceOf[GeneratedMessageCompanion[_]]
          Some(
            new ScalaPbResolvedType(clazz.asInstanceOf[Class[scalapb.GeneratedMessage]],
                                    typeUrlPrefix + "/" + typeDescriptor.getFullName,
                                    companionObject)
          )
        } else {
          None
        }
      } catch {
        case cnfe: ClassNotFoundException =>
          log.debug("Failed to load class", cnfe)
          None
      }
    })
  }

  def resolveTypeDescriptor(typeDescriptor: Descriptors.Descriptor): ResolvedType[Any] =
    reflectionCache
      .getOrElseUpdate(
        typeDescriptor.getFullName,
        Try {
          val maybeResolvedType = if (prefer == Prefer.Java) {
            tryResolveJavaPbType(typeDescriptor) orElse
            tryResolveScalaPbType(typeDescriptor)
          } else {
            tryResolveScalaPbType(typeDescriptor) orElse
            tryResolveJavaPbType(typeDescriptor)
          }

          maybeResolvedType match {
            case Some(resolvedType) => resolvedType.asInstanceOf[ResolvedType[Any]]
            case None =>
              throw SerializationException("Could not determine serializer for type " + typeDescriptor.getFullName)
          }
        }
      )
      .get

  def resolveServiceDescriptor(
      serviceDescriptor: Descriptors.ServiceDescriptor
  ): Map[String, ResolvedServiceMethod[_, _]] =
    serviceDescriptor.getMethods.asScala.map { method =>
      method.getName -> ResolvedServiceMethod(method,
                                              resolveTypeDescriptor(method.getInputType),
                                              resolveTypeDescriptor(method.getOutputType))
    }.toMap

  private def resolveTypeUrl(typeName: String): Option[ResolvedType[_]] =
    allTypes.get(typeName).map(resolveTypeDescriptor)

  private def decodeJson(typeUrl: String, bytes: ByteString) = {
    val jsonType = typeUrl.substring(CloudStateJson.length)
    reflectionCache
      .getOrElseUpdate(
        "$json$" + jsonType,
        Try {
          try {
            val jsonClass = classLoader.loadClass(jsonType)
            if (jsonClass.getAnnotation(classOf[Jsonable]) == null) {
              throw SerializationException(
                s"Illegal CloudEvents json class, no @Jsonable annotation is present: $jsonType"
              )
            }
            new JacksonResolvedType(jsonClass.asInstanceOf[Class[Any]],
                                    typeUrl,
                                    objectMapper.readerFor(jsonClass),
                                    objectMapper.writerFor(jsonClass))
          } catch {
            case cnfe: ClassNotFoundException =>
              throw SerializationException("Could not load JSON class: " + jsonType, cnfe)
          }
        }
      )
      .get
      .parseFrom(bytesToPrimitive(BytesPrimitive, bytes))
  }

  def encodeJava(value: Any): JavaPbAny =
    value match {
      case javaPbAny: JavaPbAny => javaPbAny
      case scalaPbAny: ScalaPbAny => ScalaPbAny.toJavaProto(scalaPbAny)
      case _ => ScalaPbAny.toJavaProto(encodeScala(value))
    }

  def encodeScala(value: Any): ScalaPbAny =
    value match {
      case javaPbAny: JavaPbAny => ScalaPbAny.fromJavaProto(javaPbAny)
      case scalaPbAny: ScalaPbAny => scalaPbAny

      case javaProtoMessage: com.google.protobuf.Message =>
        ScalaPbAny(
          typeUrlPrefix + "/" + javaProtoMessage.getDescriptorForType.getFullName,
          javaProtoMessage.toByteString
        )

      case scalaPbMessage: GeneratedMessage =>
        ScalaPbAny(
          typeUrlPrefix + "/" + scalaPbMessage.companion.scalaDescriptor.fullName,
          scalaPbMessage.toByteString
        )

      case _ if ClassToPrimitives.contains(value.getClass) =>
        val primitive = ClassToPrimitives(value.getClass)
        ScalaPbAny(primitive.fullName, primitiveToBytes(primitive, value))

      case byteString: ByteString =>
        ScalaPbAny(BytesPrimitive.fullName, primitiveToBytes(BytesPrimitive, byteString))

      case _: AnyRef if value.getClass.getAnnotation(classOf[Jsonable]) != null =>
        val json = UnsafeByteOperations.unsafeWrap(objectMapper.writeValueAsBytes(value))
        ScalaPbAny(CloudStateJson + value.getClass.getName, primitiveToBytes(BytesPrimitive, json))

      case other =>
        throw SerializationException(
          s"Don't know how to serialize object of type ${other.getClass}. Try passing a protobuf, using a primitive type, or using a type annotated with @Jsonable."
        )
    }

  def decode(any: ScalaPbAny): Any = decode(any.typeUrl, any.value)

  def decode(any: JavaPbAny): Any = decode(any.getTypeUrl, any.getValue)

  private def decode(typeUrl: String, bytes: ByteString): Any =
    if (typeUrl.startsWith(CloudStatePrimitive)) {
      NameToPrimitives.get(typeUrl) match {
        case Some(primitive) =>
          bytesToPrimitive(primitive, bytes)
        case None =>
          throw SerializationException("Unknown primitive type url: " + typeUrl)
      }
    } else if (typeUrl.startsWith(CloudStateJson)) {
      decodeJson(typeUrl, bytes)
    } else {
      val typeName = typeUrl.split("/", 2) match {
        case Array(host, typeName) =>
          if (host != typeUrlPrefix) {
            log.warn("Message type [{}] does not match configured type url prefix [{}]",
                     typeUrl: Any,
                     typeUrlPrefix: Any)
          }
          typeName
        case _ =>
          log.warn(
            "Message type [{}] does not have a url prefix, it should have one that matchers the configured type url prefix [{}]",
            typeUrl: Any,
            typeUrlPrefix: Any
          )
          typeUrl
      }

      resolveTypeUrl(typeName) match {
        case Some(parser) =>
          parser.parseFrom(bytes)
        case None =>
          throw SerializationException("Unable to find descriptor for type: " + typeUrl)
      }
    }

  def decodeProtobuf(typeDescriptor: Descriptors.Descriptor, any: ScalaPbAny) =
    resolveTypeDescriptor(typeDescriptor).parseFrom(any.value)

}

final case class SerializationException(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)
