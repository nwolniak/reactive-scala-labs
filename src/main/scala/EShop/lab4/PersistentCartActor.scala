package EShop.lab4

import EShop.lab2.TypedCheckout
import EShop.lab2.Cart
import EShop.lab3.OrderManager
import akka.actor.Cancellable
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.concurrent.duration._

class PersistentCartActor {

  import EShop.lab2.TypedCartActor._

  val cartTimerDuration: FiniteDuration = 5.seconds

  private def scheduleTimer(context: ActorContext[Command], duration: FiniteDuration): Cancellable =
    context.scheduleOnce(duration, context.self, ExpireCart)

  def apply(persistenceId: PersistenceId): Behavior[Command] = Behaviors.setup { context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId,
      Empty,
      commandHandler(context),
      eventHandler(context)
    )
  }

  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state, command) => {
    state match {
      case Empty =>
        command match {
          case AddItem(item) => Effect.persist(ItemAdded(item, System.currentTimeMillis()))
          case GetItems(sender) =>
            Effect.none
              .thenRun(_ => sender ! Cart.empty)
          case _ => Effect.none
        }

      case NonEmpty(cart, timer) =>
        command match {
          case AddItem(item) =>
            timer.cancel()
            Effect.persist(ItemAdded(item, System.currentTimeMillis()))
          case RemoveItem(item) if (cart contains item) && (cart.size == 1) =>
            timer.cancel()
            Effect.persist(CartEmptied)
          case RemoveItem(item) if cart contains item =>
            timer.cancel()
            Effect.persist(ItemRemoved(item, System.currentTimeMillis()))
          case RemoveItem(_) => Effect.none
          case ExpireCart =>
            Effect.persist(CartExpired)
          case StartCheckout(orderManagerRef) =>
            timer.cancel()
            val typedCheckout = context.spawn(new TypedCheckout(context.self).start, "typedCheckoutActor")
            Effect.persist(CheckoutStarted(typedCheckout))
              .thenRun(_ => {
                typedCheckout ! TypedCheckout.StartCheckout
                orderManagerRef ! OrderManager.ConfirmCheckoutStarted(typedCheckout)
              })
          case GetItems(sender) =>
            Effect.none
              .thenRun(_ => sender ! cart)
          case _ => Effect.none
        }

      case InCheckout(cart) =>
        command match {
          case ConfirmCheckoutCancelled => Effect.persist(CheckoutCancelled(System.currentTimeMillis()))
          case ConfirmCheckoutClosed => Effect.persist(CheckoutClosed)
          case GetItems(sender) =>
            Effect.none
              .thenRun(_ => sender ! cart)
          case _ => Effect.none
        }
    }
  }

  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) => {
    def calculateTimeDiff(invocationTime: Long): FiniteDuration = cartTimerDuration - (System.currentTimeMillis() - invocationTime).millis
    event match {
      case CheckoutStarted(_)        => InCheckout(state.cart)
      case ItemAdded(item, invocationTime)           => NonEmpty(state.cart.addItem(item), scheduleTimer(context, calculateTimeDiff(invocationTime)))
      case ItemRemoved(item, invocationTime)         => NonEmpty(state.cart.removeItem(item), scheduleTimer(context, calculateTimeDiff(invocationTime)))
      case CartEmptied | CartExpired => Empty
      case CheckoutClosed            => Empty
      case CheckoutCancelled(invocationTime)        => NonEmpty(state.cart, scheduleTimer(context, calculateTimeDiff(invocationTime)))
    }
  }

}
