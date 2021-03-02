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

import java.lang.annotation.Annotation
import java.lang.reflect.{AccessibleObject, Executable, Member, Method, ParameterizedType, Type, WildcardType}
import java.util.Optional

import io.cloudstate.jvmsupport.{Context, EntityContext, EntityId, ServiceCallFactory}
import com.google.protobuf.{Any => JavaPbAny}

import scala.reflect.ClassTag

private[impl] object ReflectionHelper {

  def getAllDeclaredMethods(clazz: Class[_]): Seq[Method] =
    if (clazz.getSuperclass == null || clazz.getSuperclass == classOf[Object]) {
      clazz.getDeclaredMethods
    } else {
      clazz.getDeclaredMethods.toVector ++ getAllDeclaredMethods(clazz.getSuperclass)
    }

  def isWithinBounds(clazz: Class[_], upper: Class[_], lower: Class[_]): Boolean =
    upper.isAssignableFrom(clazz) && clazz.isAssignableFrom(lower)

  def ensureAccessible[T <: AccessibleObject](accessible: T): T = {
    if (!accessible.isAccessible) {
      accessible.setAccessible(true)
    }
    accessible
  }

  def getCapitalizedName(member: Member): String =
    // These use unicode upper/lower case definitions, rather than locale sensitive,
    // which is what we want.
    if (member.getName.charAt(0).isLower) {
      member.getName.charAt(0).toUpper + member.getName.drop(1)
    } else member.getName

  final case class InvocationContext[+C <: Context](mainArgument: AnyRef, context: C)
  trait ParameterHandler[-C <: Context] extends (InvocationContext[C] => AnyRef)
  case object ContextParameterHandler extends ParameterHandler[Context] {
    override def apply(ctx: InvocationContext[Context]): AnyRef = ctx.context.asInstanceOf[AnyRef]
  }
  final case class MainArgumentParameterHandler[C <: Context](argumentType: Class[_]) extends ParameterHandler[C] {
    override def apply(ctx: InvocationContext[C]): AnyRef = ctx.mainArgument
  }
  final case object EntityIdParameterHandler extends ParameterHandler[EntityContext] {
    override def apply(ctx: InvocationContext[EntityContext]): AnyRef = ctx.context.entityId()
  }
  final case object ServiceCallFactoryParameterHandler extends ParameterHandler[Context] {
    override def apply(ctx: InvocationContext[Context]): AnyRef = ctx.context.serviceCallFactory()
  }

  final case class MethodParameter(method: Executable, param: Int) {
    def parameterType: Class[_] = method.getParameterTypes()(param)
    def genericParameterType: Type = method.getGenericParameterTypes()(param)
    def annotation[A <: Annotation: ClassTag] =
      method
        .getParameterAnnotations()(param)
        .find(a => implicitly[ClassTag[A]].runtimeClass.isInstance(a))
  }

  def getParameterHandlers[C <: Context: ClassTag](method: Executable)(
      extras: PartialFunction[MethodParameter, ParameterHandler[C]] = PartialFunction.empty
  ): Array[ParameterHandler[C]] = {
    val handlers = Array.ofDim[ParameterHandler[_]](method.getParameterCount)
    for (i <- 0 until method.getParameterCount) {
      val parameter = MethodParameter(method, i)
      // First match things that we can be specific about
      val contextClass = implicitly[ClassTag[C]].runtimeClass
      handlers(i) =
        if (isWithinBounds(parameter.parameterType, classOf[Context], contextClass))
          ContextParameterHandler
        else if (classOf[Context].isAssignableFrom(parameter.parameterType))
          // It's a context parameter who is not within the lower bound of the contexts supported by this method
          throw new RuntimeException(
            s"Unsupported context parameter on ${method.getName}, ${parameter.parameterType} must be the same or a super type of $contextClass"
          )
        else if (parameter.parameterType == classOf[ServiceCallFactory])
          ServiceCallFactoryParameterHandler
        else if (parameter.annotation[EntityId].isDefined) {
          if (parameter.parameterType != classOf[String]) {
            throw new RuntimeException(
              s"@EntityId annotated parameter on method ${method.getName} has type ${parameter.parameterType}, must be String."
            )
          }
          EntityIdParameterHandler
        } else
          extras.applyOrElse(parameter, (p: MethodParameter) => MainArgumentParameterHandler(p.parameterType))
    }
    handlers.asInstanceOf[Array[ParameterHandler[C]]]
  }

  final class CommandHandlerInvoker[CommandContext <: Context: ClassTag](
      val method: Method,
      val serviceMethod: ResolvedServiceMethod[_, _],
      extraParameters: PartialFunction[MethodParameter, ParameterHandler[CommandContext]] = PartialFunction.empty
  ) {

    private val name = serviceMethod.descriptor.getFullName
    private val parameters = ReflectionHelper.getParameterHandlers[CommandContext](method)(extraParameters)

    if (parameters.count(_.isInstanceOf[MainArgumentParameterHandler[_]]) > 1) {
      throw new RuntimeException(
        s"CommandHandler method $method must defined at most one non context parameter to handle commands, the parameters defined were: ${parameters
          .collect { case MainArgumentParameterHandler(clazz) => clazz.getName }
          .mkString(",")}"
      )
    }
    parameters.foreach {
      case MainArgumentParameterHandler(inClass) if !inClass.isAssignableFrom(serviceMethod.inputType.typeClass) =>
        throw new RuntimeException(
          s"Incompatible command class $inClass for command $name, expected ${serviceMethod.inputType.typeClass}"
        )
      case _ =>
    }

    private def serialize(result: AnyRef) =
      JavaPbAny
        .newBuilder()
        .setTypeUrl(serviceMethod.outputType.typeUrl)
        .setValue(serviceMethod.outputType.asInstanceOf[ResolvedType[Any]].toByteString(result))
        .build()

    private def verifyOutputType(t: Type): Unit =
      if (!serviceMethod.outputType.typeClass.isAssignableFrom(getRawType(t))) {
        throw new RuntimeException(
          s"Incompatible return class $t for command $name, expected ${serviceMethod.outputType.typeClass}"
        )
      }

    private val handleResult: AnyRef => Optional[JavaPbAny] = if (method.getReturnType == Void.TYPE) { _ =>
      Optional.empty()
    } else if (method.getReturnType == classOf[Optional[_]]) {
      verifyOutputType(getFirstParameter(method.getGenericReturnType))

      { result =>
        val asOptional = result.asInstanceOf[Optional[AnyRef]]
        if (asOptional.isPresent) {
          Optional.of(serialize(asOptional.get()))
        } else {
          Optional.empty()
        }
      }
    } else {
      verifyOutputType(method.getReturnType)
      result => Optional.of(serialize(result))
    }

    def invoke(obj: AnyRef, command: JavaPbAny, context: CommandContext): Optional[JavaPbAny] = {
      val decodedCommand = serviceMethod.inputType.parseFrom(command.getValue).asInstanceOf[AnyRef]
      val ctx = InvocationContext(decodedCommand, context)
      val result = method.invoke(obj, parameters.map(_.apply(ctx)): _*)
      handleResult(result)
    }
  }

  private def getRawType(t: Type): Class[_] = t match {
    case clazz: Class[_] => clazz
    case pt: ParameterizedType => getRawType(pt.getRawType)
    case wct: WildcardType => getRawType(wct.getUpperBounds.headOption.getOrElse(classOf[Object]))
    case _ => classOf[Object]
  }

  def getFirstParameter(t: Type): Class[_] =
    t match {
      case pt: ParameterizedType =>
        getRawType(pt.getActualTypeArguments()(0))
      case _ =>
        classOf[AnyRef]
    }

  /**
   * Verifies that none of the given methods have CloudState annotations that are not allowed.
   *
   * This is designed to eagerly catch mistakes such as importing the wrong CommandHandler annotation.
   *
   * TODO: Revalidate this
   */
  def validateNoBadMethods(methods: Seq[Method],
                           entity: Class[_ <: Annotation],
                           allowed: Set[Class[_ <: Annotation]]): Unit =
    methods.foreach { method =>
      method.getAnnotations.foreach { annotation =>
        if (annotation.annotationType().getAnnotation(classOf[CloudStateAnnotation]) != null && !allowed(
              annotation.annotationType()
            )) {
          val maybeAlternative = allowed.find(_.getSimpleName == annotation.annotationType().getSimpleName)
          throw new RuntimeException(
            s"Annotation @${annotation.annotationType().getName} on method ${method.getDeclaringClass.getName}." +
            s"${method.getName} not allowed in @${entity.getName} annotated entity." +
            maybeAlternative.fold("")(alterative => s" Did you mean to use @${alterative.getName}?")
          )
        }
      }
    }
}
