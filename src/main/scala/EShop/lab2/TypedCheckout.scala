package EShop.lab2

import EShop.lab3.{OrderManager, Payment}
import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}

import scala.concurrent.duration._
import scala.language.postfixOps

object TypedCheckout {

  sealed trait Command
  case object StartCheckout                                                                  extends Command
  case class SelectDeliveryMethod(method: String)                                            extends Command
  case object CancelCheckout                                                                 extends Command
  case object ExpireCheckout                                                                 extends Command
  case class SelectPayment(payment: String, orderManagerRef: ActorRef[OrderManager.Command]) extends Command
  case object ExpirePayment                                                                  extends Command
  case object ConfirmPaymentReceived                                                         extends Command
  case object PaymentRejected                                                                extends Command
  case object PaymentRestarted                                                               extends Command

  sealed trait Event
  case object CheckOutClosed                                    extends Event
  case class PaymentStarted(payment: ActorRef[Payment.Command], invocationTime: Long) extends Event
  case class CheckoutStarted(invocationTime: Long)                                   extends Event
  case object CheckoutCancelled                                 extends Event
  case class DeliveryMethodSelected(method: String, invocationTime: Long)             extends Event

  sealed abstract class State(val timerOpt: Option[Cancellable])
  case object WaitingForStart                           extends State(None)
  case class SelectingDelivery(timer: Cancellable)      extends State(Some(timer))
  case class SelectingPaymentMethod(timer: Cancellable) extends State(Some(timer))
  case object Closed                                    extends State(None)
  case object Cancelled                                 extends State(None)
  case class ProcessingPayment(timer: Cancellable)      extends State(Some(timer))
}

class TypedCheckout(
  cartActor: ActorRef[TypedCartActor.Command]
) {
  import TypedCheckout._
  val checkoutTimerDuration: FiniteDuration = 1 seconds
  val paymentTimerDuration: FiniteDuration  = 1 seconds

  private def checkoutTimer(context: ActorContext[Command]): Cancellable = context.scheduleOnce(checkoutTimerDuration, context.self, ExpireCheckout)
  private def paymentTImer(context: ActorContext[Command]): Cancellable = context.scheduleOnce(paymentTimerDuration, context.self, ExpirePayment)

  def start: Behavior[TypedCheckout.Command] = Behaviors.receive((context, message) => message match {
    case StartCheckout => selectingDelivery(checkoutTimer(context))
  })

  def selectingDelivery(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive((context, message) => message match {
    case SelectDeliveryMethod(method: String) =>
      timer.cancel()
      selectingPaymentMethod(checkoutTimer(context))
    case CancelCheckout =>
      timer.cancel()
      cancelled
    case ExpireCheckout =>
      timer.cancel()
      cancelled
  })

  def selectingPaymentMethod(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive((context, message) => message match {
    case SelectPayment(payment, orderManagerRef) =>
      val typedPaymentActor = context.spawn(new Payment(payment, orderManagerRef, context.self).start, "payment")
      orderManagerRef ! OrderManager.ConfirmPaymentStarted(typedPaymentActor)
      timer.cancel()
      processingPayment(paymentTImer(context))
    case CancelCheckout =>
      timer.cancel()
      cancelled
    case ExpireCheckout =>
      timer.cancel()
      cancelled
  })

  def processingPayment(timer: Cancellable): Behavior[TypedCheckout.Command] = Behaviors.receive((context, message) => message match {
    case ConfirmPaymentReceived =>
      cartActor ! TypedCartActor.ConfirmCheckoutClosed
      timer.cancel()
      closed
    case CancelCheckout =>
      timer.cancel()
      cancelled
    case ExpirePayment =>
      timer.cancel()
      cancelled
  })

  def cancelled: Behavior[Command] = Behaviors.receiveMessage(_ => Behaviors.same)

  def closed: Behavior[Command] = Behaviors.receiveMessage(_ => Behaviors.same)

}
