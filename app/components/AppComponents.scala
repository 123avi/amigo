package components

import akka.stream.scaladsl.Source
import akka.typed._
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth.{ AWSCredentialsProviderChain, InstanceProfileCredentialsProvider }
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.gu.cm.{ ConfigurationLoader, Identity }
import com.gu.googleauth.GoogleAuthConfig
import org.joda.time.Duration
import play.api.ApplicationLoader.Context
import play.api.libs.streams.Streams
import play.api.{ BuiltInComponentsFromContext, Configuration, Logger }
import play.api.i18n.I18nComponents
import play.api.libs.iteratee.Concurrent
import play.api.libs.ws.ahc.AhcWSComponents
import play.api.routing.Router

import ansible.PlaybookGenerator
import data.{ Dynamo, Recipes }
import prism.Prism
import packer._
import event.{ ActorSystemWrapper, BakeEvent, Behaviours }
import schedule.{ BakeScheduler, ScheduledBakeRunner }
import controllers._
import packer.BuildService.CreateImageContext

import router.Routes

class AppComponents(context: Context)
    extends BuiltInComponentsFromContext(context)
    with AhcWSComponents
    with I18nComponents {

  val identity = {
    import com.gu.cm.PlayImplicits._
    Identity.whoAmI("amigo", context.environment.mode)
  }
  override lazy val configuration: Configuration = context.initialConfiguration ++ ConfigurationLoader.playConfig(identity, context.environment.mode)

  def mandatoryConfig(key: String): String = configuration.getString(key).getOrElse(sys.error(s"Missing config key: $key"))

  implicit val dynamo = {
    val awsCreds = new AWSCredentialsProviderChain(
      new ProfileCredentialsProvider("deployTools"),
      new ProfileCredentialsProvider(),
      new InstanceProfileCredentialsProvider
    )
    val region = configuration.getString("aws.region").map(Regions.fromName).getOrElse(Regions.EU_WEST_1)
    val dynamoClient: AmazonDynamoDBClient = new AmazonDynamoDBClient(awsCreds).withRegion(region)
    new Dynamo(dynamoClient, identity.stage)
  }
  dynamo.initTables()

  val (eventsEnumerator, eventsChannel) = Concurrent.broadcast[BakeEvent]
  val eventsSource = Source.fromPublisher(Streams.enumeratorToPublisher(eventsEnumerator))
  val eventBusActorSystem = {
    val eventListeners = Map(
      "channelSender" -> Props(Behaviours.sendToChannel(eventsChannel)),
      "logWriter" -> Props(Behaviours.writeToLog),
      "dynamoWriter" -> Props(Behaviours.writeToDynamo)
    )
    ActorSystem[BakeEvent]("EventBus", Props(Behaviours.guardian(eventListeners)))
  }
  implicit val eventBus = new ActorSystemWrapper(eventBusActorSystem)

  val googleAuthConfig = GoogleAuthConfig(
    clientId = mandatoryConfig("google.clientId"),
    clientSecret = mandatoryConfig("google.clientSecret"),
    redirectUrl = mandatoryConfig("google.redirectUrl"),
    domain = Some("guardian.co.uk"),
    maxAuthAge = Some(Duration.standardDays(90)),
    enforceValidity = true
  )

  implicit val packerConfig = PackerConfig(
    stage = identity.stage,
    vpcId = configuration.getString("packer.vpcId"),
    subnetId = configuration.getString("packer.subnetId"),
    instanceProfile = configuration.getString("packer.instanceProfile")
  )

  val createImageContext = CreateImageContext(eventBus, packerConfig, wsClient)
  val createImage = BuildService.createImage(
    PlaybookGenerator.generatePlaybook,
    TempFiles.writePlaybookToTempFile,
    Prism.findAllAWSAccountNumbers,
    PackerBuildConfigGenerator.generatePackerBuildConfig,
    TempFiles.writePackerBuildConfigToTempFile,
    PackerRunner.executePacker
  ) _

  val scheduledBakeRunner = {
    val enabled = identity.stage == "PROD" // don't run scheduled bakes on dev machines
    new ScheduledBakeRunner(enabled, createImage, createImageContext)
  }
  val bakeScheduler = new BakeScheduler(scheduledBakeRunner)

  Logger.info("Registering all scheduled bakes with the scheduler")
  bakeScheduler.initialise(Recipes.list())

  val rootController = new RootController(googleAuthConfig)
  val baseImageController = new BaseImageController(googleAuthConfig, messagesApi)
  val roleController = new RoleController(googleAuthConfig)
  val recipeController = new RecipeController(bakeScheduler, googleAuthConfig, messagesApi)
  val bakeController = new BakeController(eventsSource, createImage, createImageContext, googleAuthConfig, messagesApi)
  val authController = new Auth(googleAuthConfig)(wsClient)
  val assets = new controllers.Assets(httpErrorHandler)
  lazy val router: Router = new Routes(
    httpErrorHandler,
    rootController,
    baseImageController,
    roleController,
    recipeController,
    bakeController,
    authController,
    assets)
}
