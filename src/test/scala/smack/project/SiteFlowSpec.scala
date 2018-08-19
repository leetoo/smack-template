package smack.project

import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.{DefaultTimeout, TestKitBase}
import com.fasterxml.uuid.Generators
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpec}
import smack.backend.controllers.SiteController
import smack.common.mashallers.Marshalling
import smack.common.utils.Helpers
import smack.commons.utils.DatabaseUtils
import smack.database.MigrationController
import smack.database.migrations._
import smack.frontend.routes.SiteRoute
import smack.frontend.routes.SiteRoute.{SiteCreating, SiteDeleting, SiteUpdating, SitesListing}
import smack.models.structures.Site

class SiteFlowSpec extends WordSpec with ScalatestRouteTest with TestKitBase with BeforeAndAfterAll with Matchers with Marshalling with DefaultTimeout {

  lazy implicit val config: Config = ConfigFactory.load("test")
  lazy val log = Logging(system, this.getClass.getName)
  val migrationController: MigrationController = MigrationController.createMigrationController(system,
    Seq(CreateSitesByIdTable, CreateSitesByTrackingIdTable, CreateSitesByUsersTable))

  lazy val siteController: ActorRef = system.actorOf(SiteController.props, SiteController.name)
  lazy val siteRoute: Route = SiteRoute(siteController).route

  val userId: String = "327ccc3c-a3bf-11e8-98d0-529269fb1459"

  protected override def createActorSystem(): ActorSystem = ActorSystem("siteFlowSpec", config)

  protected override def beforeAll(): Unit = {
    migrationController.migrate(createKeyspace = true)
  }

  protected override def afterAll(): Unit = {
    DatabaseUtils.dropTestKeyspace()
    shutdown()
  }

  "site request flow" should {

    "return an empty list for an user without sites" in {
      Get("/sites", SitesListing(userId)) ~> siteRoute ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[List[Site]] shouldBe empty
      }
    }

    "fail when finding invalid or non existing sites" in {
      Get("/sites/malformedUUID") ~> siteRoute ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual Helpers.getError("badUUID").get
      }

      Get(s"/sites/${Generators.timeBasedGenerator().generate().toString}") ~> siteRoute ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "create a valid site and find it" in {
      var sites: List[Site] = List()

      Post("/sites", SiteCreating(userId, "example.com"))~> siteRoute ~> check {
        status shouldEqual StatusCodes.OK
        sites = responseAs[Option[Site]].get :: sites
      }

      Post("/sites", SiteCreating(userId, "another-example.com"))~> siteRoute ~> check {
        status shouldEqual StatusCodes.OK
        sites = responseAs[Option[Site]].get :: sites
      }

      Get(s"/sites/${sites.head.id}") ~> siteRoute ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Option[Site]] shouldEqual sites.headOption
      }

      Get("/sites", SitesListing(userId)) ~> siteRoute ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[List[Site]] should contain theSameElementsAs sites.map(_.copy(domain = "", trackingId = ""))
      }
    }

    "fail when trying to create a site from an invalid userId" in {
      Post("/sites", SiteCreating("malformedUUID", "foobar.com"))~> siteRoute ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual Helpers.getError("badUUID").get
      }
    }

    "update a valid site" in {
      var site: Option[Site] = None

      Post("/sites", SiteCreating(userId, "wrong-site.com"))~> siteRoute ~> check {
        status shouldEqual StatusCodes.OK
        site = responseAs[Option[Site]]
      }

      Put(s"/sites/${site.get.id}", SiteUpdating("correct-site.com")) ~> siteRoute ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Option[Site]] shouldEqual Some(site.get.copy(domain = "correct-site.com"))
      }

      Get(s"/sites/${site.get.id}") ~> siteRoute ~> check {
        responseAs[Option[Site]] shouldEqual Some(site.get.copy(domain = "correct-site.com"))
      }
    }

    "fail when trying to update an invalid site" in {
      Put("/sites/malformedUUID", SiteUpdating(domain = "foobar.com")) ~> siteRoute ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual Helpers.getError("badUUID").get
      }

      Put(s"/sites/${Generators.timeBasedGenerator().generate().toString}", SiteUpdating(domain = "foobar.com")) ~> siteRoute ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "delete a valid site" in {
      var site: Option[Site] = None

      Post("/sites", SiteCreating(userId, "bad-site.com"))~> siteRoute ~> check {
        status shouldEqual StatusCodes.OK
        site = responseAs[Option[Site]]
      }

      Delete(s"/sites/${site.get.id}", SiteDeleting(userId = userId)) ~> siteRoute ~> check {
        status shouldEqual StatusCodes.OK
      }

      Get(s"/sites/${site.get.id}") ~> siteRoute ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "fail when trying to delete an invalid site" in {
      Delete("/sites/malformedUUID", SiteDeleting(userId = userId)) ~> siteRoute ~> check {
        status shouldEqual StatusCodes.BadRequest
        responseAs[String] shouldEqual Helpers.getError("badUUID").get
      }

      Delete(s"/sites/${Generators.timeBasedGenerator().generate().toString}", SiteDeleting(userId = userId)) ~> siteRoute ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

  }

}
