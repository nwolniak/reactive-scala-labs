package EShop.lab3

import EShop.lab2.TypedCartActor.ConfirmCheckoutClosed
import EShop.lab2.{TypedCartActor, TypedCheckout}
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class TypedCheckoutTest
  extends ScalaTestWithActorTestKit
  with AnyFlatSpecLike
  with BeforeAndAfterAll
  with Matchers
  with ScalaFutures {

  import TypedCheckout._

  override def afterAll: Unit =
    testKit.shutdownTestKit()

  it should "Send close confirmation to cart" in {
    val probe = testKit.createTestProbe[TypedCartActor.Command]()
    val typedCheckout = testKit.spawn(new TypedCheckout(probe.ref).start)
    val orderManager = testKit.spawn(new OrderManager().start)
    typedCheckout ! StartCheckout
    typedCheckout ! SelectDeliveryMethod("car")
    typedCheckout ! SelectPayment("card", orderManager)
    typedCheckout ! ConfirmPaymentReceived
    probe.expectMessage(ConfirmCheckoutClosed)
  }

}
