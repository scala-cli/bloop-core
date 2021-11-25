package bloop.launcher

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import bloop.internal.build.BuildInfo
import bloop.bloopgun.util.Environment
import bloop.logging.{BspClientLogger, RecordingLogger}
import bloop.util.TestUtil
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import sbt.internal.util.MessageOnlyException

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc._
import scala.util.control.NonFatal
import bloop.bloopgun.ServerConfig

import bloop.launcher.core.{Feedback => LauncherFeedback}
import bloop.bloopgun.util.{Feedback => BloopgunFeedback}
import bloop.bloopgun.core.AvailableAtPath

object LatestStableLauncherSpec extends LauncherSpec("1.3.2", () => Left(9014))
object LatestMasterLauncherSpec extends LauncherSpec(BuildInfo.version, () => Left(9014))
object LatestMasterLauncherDomainSocketSpec
    extends LauncherSpec(
      BuildInfo.version,
      () => Right((TestUtil.tmpDir(strictPermissions = true), "bloop_tests\\daemon"))
    )

class LauncherSpec(
    bloopVersion: String,
    bloopServerPortOrDaemonDir: () => Either[Int, (Path, String)]
) extends LauncherBaseSuite(bloopVersion, BuildInfo.bspVersion) {

  // Copied from elsewhere here
  private object Num {
    def unapply(s: String): Option[Int] =
      if (s.nonEmpty && s.forall(_.isDigit)) Some(s.toInt)
      else None
  }
  private def bloopOrg(version: String): String =
    version.split("[-.]") match {
      case Array(Num(maj), Num(min), Num(patch), _*) =>
        import scala.math.Ordering.Implicits._
        if (Seq(maj, min, patch) >= Seq(1, 4, 11) && version != "1.4.11")
          "io.github.alexarchambault.bleep"
        else "ch.epfl.scala"
      case _ => "ch.epfl.scala"
    }

  private final val bloopDependency =
    s"${bloopOrg(bloopVersion)}:bloop-frontend_2.12:${bloopVersion}"
  test("fail if arguments are empty") {
    setUpLauncher(shellWithPython, bloopServerPortOrDaemonDir()) { run =>
      val status = run.launcher.cli(Array())
      assert(status == LauncherStatus.FailedToParseArguments)
      assert(run.logs.exists(_.contains(LauncherFeedback.NoBloopVersion)))
    }
  }

  ignore("fail for bloop version not supporting launcher") {
    setUpLauncher(shellWithPython, bloopServerPortOrDaemonDir()) { run =>
      val args = Array("1.0.0")
      val status = run.launcher.cli(args)
      assert(status == LauncherStatus.FailedToInstallBloop)
    }
  }

  test("when bloop is uninstalled, resolve bloop, start and connect to server via BSP") {
    val bloopServerPortOrDaemonDir0 = bloopServerPortOrDaemonDir()
    val listenOn = bloopServerPortOrDaemonDir0.left
      .map(port => (None, Some(port)))
      .map {
        case (path, pipeName) =>
          (Some(path), Some(pipeName))
      }
    val defaultConfig = ServerConfig(listenOn = listenOn)
    val result = runBspLauncherWithEnvironment(
      Array(bloopVersion),
      shellWithPython,
      bloopServerPortOrDaemonDir0
    )
    val expectedLogs = List(
      BloopgunFeedback.resolvingDependency(bloopDependency),
      BloopgunFeedback.startingBloopServer(defaultConfig),
      LauncherFeedback.openingBspConnection(Nil)
    )

    result.throwIfFailed
    assertLogsContain(expectedLogs, result.launcherLogs, Nil)
  }

  /**
   * Tests the behavior of `--skip-bsp-connection` where the launcher has to
   * install bloop, start a server and return early without establishing a
   * bsp connection.
   *
   * This mode is useful for developer tools wanting to have a way to immediately
   * depend on bloop and start using it without bothering if it's installed or not
   * in a given machine.
   */
  test(
    "when bloop is uninstalled and `--skip-bsp-connection`, resolve bloop, start and connect to server via BSP"
  ) {
    val args = Array(bloopVersion, "--skip-bsp-connection")

    val bloopServerPortOrDaemonDir0 = bloopServerPortOrDaemonDir()
    val listenOn = bloopServerPortOrDaemonDir0.left
      .map(port => (None, Some(port)))
      .map {
        case (path, pipeName) =>
          (Some(path), Some(pipeName))
      }
    val defaultConfig = ServerConfig(listenOn = listenOn)

    setUpLauncher(
      in = System.in,
      out = System.out,
      startedServer = Promise[Unit](),
      shell = shellWithPython,
      bloopServerPortOrDaemonDir = bloopServerPortOrDaemonDir0
    ) { run =>
      val status = run.launcher.cli(args)
      val expectedLogs = List(
        BloopgunFeedback.resolvingDependency(bloopDependency),
        BloopgunFeedback.startingBloopServer(defaultConfig)
      )

      val prohibitedLogs = List(
        LauncherFeedback.openingBspConnection(Nil)
      )

      assert(LauncherStatus.SuccessfulRun == status)
      assertLogsContain(expectedLogs, run.logs, prohibitedLogs)
    }
  }
}
