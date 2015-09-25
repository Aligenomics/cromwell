package cromwell

import java.io.File
import java.util.UUID

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.testkit._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import cromwell.CromwellTestkitSpec._
import cromwell.binding._
import cromwell.binding.values.{WdlArray, WdlFile, WdlValue}
import cromwell.engine.ExecutionIndex.ExecutionIndex
import cromwell.engine._
import cromwell.engine.backend.StdoutStderr
import cromwell.engine.backend.local.LocalBackend
import cromwell.engine.workflow.WorkflowManagerActor
import cromwell.parser.BackendType
import cromwell.util.FileUtil._
import cromwell.util.SampleWdl
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{BeforeAndAfterAll, Matchers, OneInstancePerTest, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag
import scala.util.matching.Regex

object CromwellTestkitSpec {
  val ConfigText =
    """
      |akka {
      |  loggers = ["akka.event.slf4j.Slf4jLogger", "akka.testkit.TestEventListener"]
      |  loglevel = "INFO"
      |  actor {
      |    debug {
      |       receive = on
      |    }
      |  }
      |}
    """.stripMargin

  val timeoutDuration = 10 seconds
  implicit val timeout = new Timeout(timeoutDuration)
}

abstract class CromwellTestkitSpec(name: String) extends TestKit(ActorSystem(name, ConfigFactory.parseString(ConfigText)))
with DefaultTimeout with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll with ScalaFutures with OneInstancePerTest {

  def startingCallsFilter[T](callNames: String*)(block: => T): T =
    waitForPattern(s"starting calls: ${callNames.mkString(", ")}$$")(block)

  def waitForHandledMessage[T](named: String)(block: => T): T = {
    waitForHandledMessagePattern(s"^received handled message $named")(block)
  }

  def waitForHandledMessagePattern[T](pattern: String)(block: => T): T = {
    EventFilter.info(pattern = pattern, occurrences = 1).intercept {
      block
    }
  }

  /**
   * Performs the following steps:
   *
   * <ol>
   * <li> Sends the specified message to the implicitly passed `ActorRef` via an `ask`.
   * <li> Collects the `Future[Any]` response.
   * <li> Downcasts the `Future[Any]` to a `Future[M]`.
   * <li> Issues a blocking `Await.result` on the `Future`, yielding an `M`.
   * </ol>
   *
   */
  def messageAndWait[M: ClassTag](message: AnyRef)(implicit actorRef: ActorRef): M = {
    val futureAny = actorRef ? message
    Await.result(futureAny.mapTo[M], timeoutDuration)
  }

  /**
   * Wait for exactly one occurrence of the specified pattern in the specified block.  The block
   * is in its own parameter list for usage syntax reasons.
   */
  def waitForPattern[T](pattern: String, occurrences: Int = 1)(block: => T): T = {
    EventFilter.info(pattern = pattern, occurrences = occurrences).intercept {
      within(timeoutDuration) {
        block
      }
    }
  }

  /**
   * Akka TestKit appears to be unable to match errors generated by `log.error(Throwable, String)` with the normal
   * `EventFilter.error(...).intercept {...}` mechanism since `EventFilter.error` forces the use of a dummy exception
   * that never matches a real exception.  This method works around that problem by building an `ErrorFilter` more
   * explicitly to allow the caller to specify a `Throwable` class.
   */
  def waitForErrorWithException[T](pattern: String, throwableClass: Class[_ <: Throwable] = classOf[Throwable], occurrences: Int = 1)(block: => T): T = {
    val regex = Right[String, Regex](pattern.r)
    ErrorFilter(throwableClass, source = None, message = regex, complete = false)(occurrences = occurrences).intercept {
      block
    }
  }

  def buildWorkflowDescriptor(sampleWdl: SampleWdl, runtime: String): WorkflowDescriptor = {
    buildWorkflowDescriptor(sampleWdl, runtime, UUID.randomUUID())
  }

  def buildWorkflowDescriptor(sampleWdl: SampleWdl, runtime: String, uuid: UUID): WorkflowDescriptor = {
    val source = sampleWdl.wdlSource(runtime)
    val namespace = NamespaceWithWorkflow.load(source, BackendType.LOCAL)
    val coercedInputs = namespace.coerceRawInputs(sampleWdl.rawInputs).get
    val declarations = namespace.staticDeclarationsRecursive(coercedInputs).get
    val inputs = coercedInputs ++ declarations
    val workflowSources = WorkflowSourceFiles(source, sampleWdl.wdlJson, "{}")
    WorkflowDescriptor(WorkflowId(uuid), workflowSources)
  }

  private def buildWorkflowManagerActor(sampleWdl: SampleWdl, runtime: String) = {
    TestActorRef(new WorkflowManagerActor(new LocalBackend))
  }

  // Not great, but this is so we can test matching data structures that have WdlFiles in them more easily
  private def validateOutput(output: WdlValue, expectedOutput: WdlValue): Unit = expectedOutput match {
    case expectedFile: WdlFile if output.isInstanceOf[WdlFile] =>
      val actualFile = output.asInstanceOf[WdlFile]
      actualFile.value.toString.endsWith(expectedFile.value.toString) shouldEqual true
    case expectedArray: WdlArray if output.isInstanceOf[WdlArray] =>
      val actualArray = output.asInstanceOf[WdlArray]
      actualArray.value.size should be(expectedArray.value.size)
      (actualArray.value zip expectedArray.value) foreach {
        case (actual, expected) => validateOutput(actual, expected)
      }
    case _ =>
      output shouldEqual expectedOutput
  }

  def runWdl(sampleWdl: SampleWdl,
             eventFilter: EventFilter,
             runtime: String = "",
             terminalState: WorkflowState = WorkflowSucceeded): (TestActorRef[WorkflowManagerActor], WorkflowId) = {
    val wma = buildWorkflowManagerActor(sampleWdl, runtime)
    val workflowSources = WorkflowSourceFiles(sampleWdl.wdlSource(runtime), sampleWdl.wdlJson, "{}")
    val submitMessage = WorkflowManagerActor.SubmitWorkflow(workflowSources)
    var workflowId: WorkflowId = null
    eventFilter.intercept {
      within(timeoutDuration) {
        workflowId = Await.result(wma.ask(submitMessage).mapTo[WorkflowId], timeoutDuration)
        verifyWorkflowState(wma, workflowId, terminalState)
        wma.ask(WorkflowManagerActor.WorkflowOutputs(workflowId)).mapTo[WorkflowOutputs].futureValue
      }
    }

    (wma, workflowId)
  }

  def runWdlAndAssertOutputs(sampleWdl: SampleWdl,
                             eventFilter: EventFilter,
                             expectedOutputs: Map[FullyQualifiedName, WdlValue],
                             runtime: String = "",
                             terminalState: WorkflowState = WorkflowSucceeded): Unit = {
    val wma = buildWorkflowManagerActor(sampleWdl, runtime)
    val submitMessage = WorkflowManagerActor.SubmitWorkflow(sampleWdl.asWorkflowSources(runtime))
    eventFilter.intercept {
      within(timeoutDuration) {
        val workflowId = Await.result(wma.ask(submitMessage).mapTo[WorkflowId], timeoutDuration)
        verifyWorkflowState(wma, workflowId, terminalState)
        val outputs = wma.ask(WorkflowManagerActor.WorkflowOutputs(workflowId)).mapTo[WorkflowOutputs].futureValue
        expectedOutputs foreach { case (outputFqn, expectedValue) =>
          val actualValue = outputs.getOrElse(outputFqn, throw new RuntimeException(s"Output $outputFqn not found"))

          validateOutput(actualValue, expectedValue)
        }
      }
    }
  }

  /*
     FIXME: I renamed this as it appears to be asserting the stdout/stderr of a single call which is kinda weird for
     a full workflow type of thing
  */
  def runSingleCallWdlWithWorkflowManagerActor(wma: TestActorRef[WorkflowManagerActor],
                                               submitMsg: WorkflowManagerActor.SubmitWorkflow,
                                               eventFilter: EventFilter,
                                               fqn: FullyQualifiedName,
                                               index: ExecutionIndex,
                                               stdout: Option[Seq[String]],
                                               stderr: Option[Seq[String]],
                                               expectedOutputs: Map[FullyQualifiedName, WdlValue] = Map.empty ) = {
    eventFilter.intercept {
      within(timeoutDuration) {
        val workflowId = Await.result(wma.ask(submitMsg).mapTo[WorkflowId], timeoutDuration)
        verifyWorkflowState(wma, workflowId, WorkflowSucceeded)
        val standardStreams = Await.result(wma.ask(WorkflowManagerActor.CallStdoutStderr(workflowId, fqn)).mapTo[Seq[StdoutStderr]], timeoutDuration)
        stdout foreach { souts =>
          souts shouldEqual (standardStreams map { s => new File(s.stdout.value).slurp })
        }
        stderr foreach { serrs =>
          serrs shouldEqual (standardStreams map { s => new File(s.stderr.value).slurp })
        }
      }
    }
  }

  def runWdlWithWorkflowManagerActor(wma: TestActorRef[WorkflowManagerActor],
                                     submitMsg: WorkflowManagerActor.SubmitWorkflow,
                                     eventFilter: EventFilter,
                                     stdout: Map[FullyQualifiedName, Seq[String]],
                                     stderr: Map[FullyQualifiedName, Seq[String]],
                                     expectedOutputs: Map[FullyQualifiedName, WdlValue] = Map.empty,
                                     terminalState: WorkflowState = WorkflowSucceeded) = {
    eventFilter.intercept {
      within(timeoutDuration) {
        val workflowId = Await.result(wma.ask(submitMsg).mapTo[WorkflowId], timeoutDuration)
        verifyWorkflowState(wma, workflowId, terminalState)
        val standardStreams = Await.result(wma.ask(WorkflowManagerActor.WorkflowStdoutStderr(workflowId)).mapTo[Map[FullyQualifiedName, Seq[StdoutStderr]]], timeoutDuration)

        stdout foreach {
          case(fqn, out) if standardStreams.contains(fqn) =>
          out shouldEqual (standardStreams(fqn) map { s => new File(s.stdout.value).slurp })
        }
        stderr foreach {
          case(fqn, err) if standardStreams.contains(fqn) =>
          err shouldEqual (standardStreams(fqn) map { s => new File(s.stderr.value).slurp })
        }
      }
    }
  }

  def runWdlAndAssertStdoutStderr(sampleWdl: SampleWdl,
                                  eventFilter: EventFilter,
                                  fqn: FullyQualifiedName,
                                  index: ExecutionIndex,
                                  runtime: String = "",
                                  stdout: Option[Seq[String]] = None,
                                  stderr: Option[Seq[String]] = None) = {
    val actor = buildWorkflowManagerActor(sampleWdl, runtime)
    val workflowSources = WorkflowSourceFiles(sampleWdl.wdlSource(runtime), sampleWdl.wdlJson, "{}")
    val submitMessage = WorkflowManagerActor.SubmitWorkflow(workflowSources)
    runSingleCallWdlWithWorkflowManagerActor(actor, submitMessage, eventFilter, fqn, index, stdout, stderr)
  }

  def runWdlAndAssertWorkflowStdoutStderr(sampleWdl: SampleWdl,
                                          eventFilter: EventFilter,
                                          runtime: String = "",
                                          stdout: Map[FullyQualifiedName, Seq[String]] = Map.empty[FullyQualifiedName, Seq[String]],
                                          stderr: Map[FullyQualifiedName, Seq[String]] = Map.empty[FullyQualifiedName, Seq[String]],
                                          terminalState: WorkflowState = WorkflowSucceeded) = {
    val actor = buildWorkflowManagerActor(sampleWdl, runtime)
    // TODO: these two lines seem to be duplicated a lot
    val workflowSources = WorkflowSourceFiles(sampleWdl.wdlSource(runtime), sampleWdl.wdlJson, "{}")
    val submitMessage = WorkflowManagerActor.SubmitWorkflow(workflowSources)
    runWdlWithWorkflowManagerActor(actor, submitMessage, eventFilter, stdout, stderr, Map.empty, terminalState)
  }

  private def verifyWorkflowState(wma: ActorRef, workflowId: WorkflowId, expectedState: WorkflowState): Unit = {
    // Continuously check the state of the workflow until it is in a terminal state
    awaitCond(pollWorkflowState(wma, workflowId).exists(_.isTerminal))
    // Now that it's complete verify that we ended up in the state we're expecting
    pollWorkflowState(wma, workflowId).get should equal (expectedState)
  }

  private def pollWorkflowState(wma: ActorRef, workflowId: WorkflowId): Option[WorkflowState] = {
    Await.result(wma.ask(WorkflowManagerActor.WorkflowStatus(workflowId)).mapTo[Option[WorkflowState]], timeoutDuration)
  }
}
