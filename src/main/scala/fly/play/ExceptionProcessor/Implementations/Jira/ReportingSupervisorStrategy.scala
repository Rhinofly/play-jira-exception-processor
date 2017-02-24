package fly.play.ExceptionProcessor.Implementations.Jira

import akka.actor.{ActorContext, ActorRef, ActorSystem, ChildRestartStats, SupervisorStrategy, SupervisorStrategyConfigurator}
import akka.stream.ActorMaterializer
import fly.play.ExceptionProcessor.ErrorInformation
import play.api.libs.ws.ahc.{AhcConfigBuilder, AhcWSClient, AhcWSClientConfig}
import play.api.libs.ws.{WSClient, WSConfigParser}
import play.api.{Configuration, Environment, Mode}

class ReportingSupervisorStrategy extends SupervisorStrategyConfigurator {
  def create(): SupervisorStrategy =
    new ReportingStrategyWrapper(SupervisorStrategy.defaultStrategy, "Error during actor process")
}

class ReportingStrategyWrapper(wrapped: SupervisorStrategy, comment: String) extends SupervisorStrategy {

  def decider = wrapped.decider

  def handleChildTerminated(
    context: ActorContext,
    child: ActorRef,
    children: Iterable[ActorRef]
  ) = wrapped.handleChildTerminated(context, child, children)

  def processFailure(
    context: ActorContext,
    restart: Boolean,
    child: ActorRef,
    cause: Throwable,
    stats: ChildRestartStats,
    children: Iterable[ChildRestartStats]
  ) = {
    reportException(context, cause)
    wrapped.processFailure(context, restart, child, cause, stats, children)
  }

  private def reportException(context: ActorContext, exception: Throwable) = {
    val configuration = Configuration(context.system.settings.config)

    def withWsClient[T](code: WSClient => T): T = {
      val wsClient = {
        val environment = Environment.simple(mode = Mode.Prod)

        val parser = new WSConfigParser(configuration, environment)
        val config = AhcWSClientConfig(wsClientConfig = parser.parse())
        val builder = new AhcConfigBuilder(config)
        val ahcConfig = builder.configure().build()

        implicit val system = ActorSystem("jiraExceptionProcessor")
        implicit val materializer = ActorMaterializer()
        new AhcWSClient(ahcConfig)
      }
      try code(wsClient)
      finally wsClient.close()
    }

    withWsClient { wsClient =>
      import context.dispatcher

      new JiraExceptionProcessor(wsClient, configuration)
        .reportError(ErrorInformation(exception, comment))
    }
  }
}