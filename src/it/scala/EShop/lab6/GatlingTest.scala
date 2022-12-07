package EShop.lab6

import io.gatling.core.Predef.{Simulation, StringBody, jsonFile, rampUsers, scenario, _}
import io.gatling.http.Predef.http

import scala.concurrent.duration._
import scala.language.postfixOps

class GatlingTest extends Simulation {

  val httpProtocol = http  //values here are adjusted to cluster_demo.sh script
    .baseUrls("http://localhost:9001", "http://localhost:9002", "http://localhost:9003")
    .acceptHeader("text/plain,text/html,application/json,application/xml;")
    .userAgentHeader("Mozilla/5.0 (Windows NT 5.1; rv:31.0) Gecko/20100101 Firefox/31.0")

  val scn = scenario("BasicSimulation")
    .feed(jsonFile("C:\\Users\\Norbert\\IdeaProjects\\Scala-Labs\\reactive-scala-labs\\src\\it\\resources\\data\\queries.json").random())
    .exec(
      http("search_request")
        .post("/search")
        .body(StringBody("""{ "brand": "${brand}", "productKeyWords": ["${productKeyWords}"]}"""))
        .asJson
    )
    .pause(5)

  setUp(
    scn.inject(rampUsers(5).during(10 seconds))
  ).protocols(httpProtocol)
}