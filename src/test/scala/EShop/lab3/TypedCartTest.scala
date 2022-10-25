package EShop.lab3

import EShop.lab2.{Cart, TypedCartActor, TypedCheckout}
import akka.actor.testkit.typed.scaladsl.{BehaviorTestKit, ScalaTestWithActorTestKit, TestInbox}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

class TypedCartTest
  extends ScalaTestWithActorTestKit
  with AnyFlatSpecLike
  with BeforeAndAfterAll
  with Matchers
  with ScalaFutures {

  override def afterAll: Unit =
    testKit.shutdownTestKit()

  //use GetItems command which was added to make test easier
  it should "add item properly" in {
    import TypedCartActor._
    val typedCartActor = BehaviorTestKit(new TypedCartActor().start)
    val mainInbox = TestInbox[Cart]()
    typedCartActor.run(AddItem("car"))
    typedCartActor.run(GetItems(mainInbox.ref))
    mainInbox.expectMessage(Cart.apply(Seq("car")))
  }

  it should "be empty after adding and removing the same item" in {
    import TypedCartActor._
    val typedCartActor = testKit.spawn(new TypedCartActor().start)
    val probe = testKit.createTestProbe[Cart]()
    typedCartActor ! AddItem("car")
    typedCartActor ! RemoveItem("car")
    typedCartActor ! GetItems(probe.ref)
    probe.expectMessage(Cart.apply(Seq.empty))
  }

  it should "start checkout" in {
    import TypedCartActor._
    val typedCartActor = testKit.spawn(new TypedCartActor().start)
    val probe = testKit.createTestProbe[OrderManager.Command]()
    typedCartActor ! AddItem("car")
    typedCartActor ! StartCheckout(probe.ref)
    probe.expectMessageType[OrderManager.ConfirmCheckoutStarted]
  }
}
