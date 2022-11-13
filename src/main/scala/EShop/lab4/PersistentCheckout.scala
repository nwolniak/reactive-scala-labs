package EShop.lab4

import EShop.lab2.TypedCartActor
import EShop.lab3.{OrderManager, Payment}
import akka.actor.Cancellable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

import scala.concurrent.duration._

class PersistentCheckout {

  import EShop.lab2.TypedCheckout._

  val timerDuration: FiniteDuration = 1.seconds

  def schedule(context: ActorContext[Command], duration: FiniteDuration): Cancellable =
    context.scheduleOnce(duration, context.self, ExpireCheckout)

  def apply(cartActor: ActorRef[TypedCartActor.Command], persistenceId: PersistenceId): Behavior[Command] =
    Behaviors.setup { context =>
      EventSourcedBehavior(
        persistenceId,
        WaitingForStart,
        commandHandler(context, cartActor),
        eventHandler(context)
      )
    }

  def commandHandler(
    context: ActorContext[Command],
    cartActor: ActorRef[TypedCartActor.Command]
  ): (State, Command) => Effect[Event, State] = (state, command) => {
    state match {
      case WaitingForStart =>
        command match {
          case StartCheckout => Effect.persist(CheckoutStarted(System.currentTimeMillis()))
          case _ => Effect.none
        }

      case SelectingDelivery(timer) =>
        command match {
          case SelectDeliveryMethod(method) =>
            timer.cancel()
            Effect.persist(DeliveryMethodSelected(method, System.currentTimeMillis()))
          case CancelCheckout =>
            timer.cancel()
            Effect.persist(CheckoutCancelled)
          case ExpireCheckout =>
            timer.cancel()
            Effect.persist(CheckoutCancelled)
          case _ => Effect.none
        }

      case SelectingPaymentMethod(timer) =>
        command match {
          case SelectPayment(payment, orderManagerRef) =>
            timer.cancel()
            val typedPaymentActor = context.spawn(new Payment(payment, orderManagerRef, context.self).start, "payment")
            Effect.persist(PaymentStarted(typedPaymentActor, System.currentTimeMillis()))
              .thenRun(_ => orderManagerRef ! OrderManager.ConfirmPaymentStarted(typedPaymentActor))
          case CancelCheckout =>
            timer.cancel()
            Effect.persist(CheckoutCancelled)
          case ExpireCheckout =>
            timer.cancel()
            Effect.persist(CheckoutCancelled)
          case _ => Effect.none
        }

      case ProcessingPayment(timer) =>
        command match {
          case ConfirmPaymentReceived =>
            timer.cancel()
            Effect.persist(CheckOutClosed)
              .thenRun(_ => cartActor ! TypedCartActor.ConfirmCheckoutClosed)
          case CancelCheckout =>
            timer.cancel()
            Effect.persist(CheckoutCancelled)
          case ExpirePayment =>
            timer.cancel()
            Effect.persist(CheckoutCancelled)
          case ExpireCheckout =>
            println("EXPIRED CHECKOUT IN PROCESSING PAYMENT")
            timer.cancel()
            Effect.persist(CheckoutCancelled)
          case _ => Effect.none
        }

      case Cancelled => Effect.none

      case Closed => Effect.none
    }
  }

  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) => {
    def calculateTimeDiff(invocationTime: Long): FiniteDuration = timerDuration - (System.currentTimeMillis() - invocationTime).millis
    event match {
      case CheckoutStarted(invocationTime)           => SelectingDelivery(schedule(context, calculateTimeDiff(invocationTime)))
      case DeliveryMethodSelected(_, invocationTime) => SelectingPaymentMethod(schedule(context, calculateTimeDiff(invocationTime)))
      case PaymentStarted(_, invocationTime)         => ProcessingPayment(schedule(context, calculateTimeDiff(invocationTime)))
      case CheckOutClosed            => Closed
      case CheckoutCancelled         => Cancelled
    }
  }
}
