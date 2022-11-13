package EShop.lab2

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}

import scala.language.postfixOps
import scala.concurrent.duration._
import EShop.lab3.OrderManager

object TypedCartActor {

  sealed trait Command
  case class AddItem(item: Any)                                             extends Command
  case class RemoveItem(item: Any)                                          extends Command
  case object ExpireCart                                                    extends Command
  case class StartCheckout(orderManagerRef: ActorRef[OrderManager.Command]) extends Command
  case object ConfirmCheckoutCancelled                                      extends Command
  case object ConfirmCheckoutClosed                                         extends Command
  case class GetItems(sender: ActorRef[Cart])                               extends Command

  sealed trait Event
  case class CheckoutStarted(checkoutRef: ActorRef[TypedCheckout.Command]) extends Event
  case class ItemAdded(item: Any, invocationTime: Long)                                          extends Event
  case class ItemRemoved(item: Any, invocationTime: Long)                                        extends Event
  case object CartEmptied                                                  extends Event
  case object CartExpired                                                  extends Event
  case object CheckoutClosed                                               extends Event
  case class CheckoutCancelled(invocationTime: Long)                                            extends Event

  sealed abstract class State(val timerOpt: Option[Cancellable]) {
    def cart: Cart
  }
  case object Empty extends State(None) {
    def cart: Cart = Cart.empty
  }
  case class NonEmpty(cart: Cart, timer: Cancellable) extends State(Some(timer))
  case class InCheckout(cart: Cart)                   extends State(None)
}

class TypedCartActor {

  import TypedCartActor._

  val cartTimerDuration: FiniteDuration = 5 seconds

  private def scheduleTimer(context: ActorContext[Command]): Cancellable = context.scheduleOnce(cartTimerDuration, context.self, ExpireCart)

  def start: Behavior[Command] = empty

  def empty: Behavior[Command] = Behaviors.receive((context, message) => message match {
    case AddItem(item: Any) => nonEmpty(Cart(Seq(item)), scheduleTimer(context))

    case GetItems(sender) =>
      sender ! Cart(Seq.empty)
      empty

    case _ => Behaviors.same
  }
  )

  def nonEmpty(cart: Cart, timer: Cancellable): Behavior[Command] = Behaviors.receive((context, message) => message match {
    case AddItem(item: Any) =>
      timer.cancel()
      nonEmpty(cart.addItem(item), scheduleTimer(context))
    case RemoveItem(item: Any) if (cart contains item) && (cart.size == 1) =>
      timer.cancel()
      empty
    case RemoveItem(item: Any) if cart contains item =>
      timer.cancel()
      nonEmpty(cart.removeItem(item), scheduleTimer(context))
    case RemoveItem(item: Any) => Behaviors.same
    case GetItems(sender) =>
      timer.cancel()
      sender ! cart
      Behaviors.same
    case ExpireCart =>
      timer.cancel()
      empty
    case StartCheckout(orderManagerRef: ActorRef[OrderManager.Command]) =>
      val typedCheckout = context.spawn(new TypedCheckout(context.self).start, "typedCheckoutActor")
      typedCheckout ! TypedCheckout.StartCheckout
      orderManagerRef ! OrderManager.ConfirmCheckoutStarted(typedCheckout)
      timer.cancel()
      inCheckout(cart)
  }
  )

  def inCheckout(cart: Cart): Behavior[Command] = Behaviors.receive((context, message) => message match {
    case ConfirmCheckoutCancelled => nonEmpty(cart, scheduleTimer(context))
    case ConfirmCheckoutClosed => empty
    case _ => Behaviors.same
  })

}
