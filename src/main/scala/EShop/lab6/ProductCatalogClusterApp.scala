package EShop.lab6

import EShop.lab5.ProductCatalog.GetItems
import EShop.lab5.Server.Response
import EShop.lab5.{ProductCatalog, SearchService, Server}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{Behaviors, Routers}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives.{as, complete, entity, path, post}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.typesafe.config.ConfigFactory

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.io.StdIn
import scala.util.Try

/**
  * Spawns an actor system that will connect with the cluster and spawn `instancesPerNode` workers
  */
class ProductCatalogWorker {
  private val instancesPerNode = 3
  private val config = ConfigFactory.load()

  val system = ActorSystem[Nothing](
    Behaviors.empty,
    "ProductCatalogCluster",
    config
  )

  val searchService = new SearchService()
  for (i <- 0 to instancesPerNode)
    system.systemActorOf(ProductCatalog(searchService), s"worker$i")

  def terminate(): Unit =
    system.terminate()
}

/**
  * Spawns a seed node
  */
object ProductCatalogClusterNodeApp extends App {
  private val config = ConfigFactory.load()

  val system = ActorSystem[Nothing](
    Behaviors.empty,
    "ProductCatalogCluster",
    config
      .getConfig(Try(args(0)).getOrElse("seed-node1"))
      .withFallback(config)
  )

  Await.ready(system.whenTerminated, Duration.Inf)
}

object ProductCatalogClusterApp extends App {
  val workHttpServerInCluster = new ProductCatalogClusterServer()
  workHttpServerInCluster.run(args(0).toInt)
}

/**
  * The server that distributes all of the requests to the workers registered in the cluster via the group router.
  * Will spawn `httpWorkersNodeCount` [[ProductCatalogWorker]] instances that will each spawn `instancesPerNode`
  * [[RegisteredHttpWorker]] instances giving us `httpWorkersNodeCount` * `instancesPerNode` workers in total.
  *
  * @see https://doc.akka.io/docs/akka/current/typed/routers.html#group-router
  */
class ProductCatalogClusterServer() extends JsonSupportProductCatalog {
  private val config = ConfigFactory.load()
  private val productCatalogWorkerNodeCount = 3

  implicit val system = ActorSystem[Nothing](
    Behaviors.empty,
    "ClusterWorkRouters",
//    config.getConfig("cluster-default")
    config
  )

  implicit val scheduler = system.scheduler
  implicit val executionContext = system.executionContext

  val workersNodes = for (_ <- 0 to productCatalogWorkerNodeCount)
    yield new ProductCatalogWorker()

  val workers = system.systemActorOf(
    Routers.group(ProductCatalog.ProductCatalogServiceKey),
    "clusterWorkerRouter")

  implicit val timeout: Timeout = 5.seconds

  def routes: Route =
    path("search") {
      post {
        entity(as[Server.Request]) { request =>
          val awaitable = workers.ask(ref =>
            GetItems(request.brand, request.productKeyWords, ref))
          val result = Await.result(awaitable, 2.seconds)
          complete {
            Future.successful(
              Response(result.asInstanceOf[ProductCatalog.Items].items))
          }
        }
      }
    }

  def run(port: Int): Unit = {
    val bindingFuture = Http().newServerAt("localhost", port).bind(routes)
    println(s"Server now online.\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete { _ =>
        system.terminate()
        workersNodes.foreach(_.terminate())
      } // and shutdown when done
  }
}
