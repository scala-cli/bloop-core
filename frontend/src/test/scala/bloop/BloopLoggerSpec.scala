package bloop

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.PrintStream

import scala.Console.RED
import scala.Console.RESET
import scala.collection.mutable

import bloop.logging.BloopLogger
import bloop.logging.DebugFilter
import bloop.util.UUIDUtil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class BloopLoggerSpec {
  @Test
  def infoAndWarnMessagesGoToOut(): Unit =
    runAndCheck { logger =>
      logger.info("info")
      logger.warn("warn")
    } { (outMsgs, errMsgs) =>
      assertEquals(2, outMsgs.length.toLong)
      assertEquals(0, errMsgs.length.toLong)

      assert(isInfo(outMsgs.head))
      assert(isWarn(outMsgs.last))
    }

  @Test
  def errorMessagesGoToErr(): Unit =
    runAndCheck { logger =>
      logger.error("error")
    } { (outMsgs, errMsgs) =>
      assertEquals(0, outMsgs.length.toLong)
      assertEquals(1, errMsgs.length.toLong)

      assert(isError(errMsgs.head))
    }

  @Test
  def debugAndTraceMessagesAreIgnoredByDefault(): Unit =
    runAndCheck { logger =>
      logger.debug("debug")(DebugFilter.All)
      logger.trace(new Exception)
    } { (outMsgs, errMsgs) =>
      assertEquals(0, outMsgs.length.toLong)
      assertEquals(0, errMsgs.length.toLong)
    }

  @Test
  def debugAndTraceMessagesGoToErrInVerboseMode(): Unit =
    runAndCheck { logger =>
      val ex0 = {
        val ex = new Exception("trace0")
        ex.setStackTrace(ex.getStackTrace.take(3))
        ex
      }
      logger.error("error0")
      logger.warn("warn0")
      logger.info("info0")
      logger.debug("debug0")(DebugFilter.All)
      logger.trace(ex0)

      val verboseLogger = logger.asVerbose
      val ex1 = {
        val ex = new Exception("trace1")
        ex.setStackTrace(ex.getStackTrace.take(3))
        ex
      }
      verboseLogger.error("error1")
      verboseLogger.warn("warn1")
      verboseLogger.info("info1")
      verboseLogger.debug("debug1")(DebugFilter.All)
      verboseLogger.trace(ex1)

      val ex2 = {
        val ex = new Exception("trace2")
        ex.setStackTrace(ex.getStackTrace.take(3))
        ex
      }
      logger.error("error2")
      logger.warn("warn2")
      logger.info("info2")
      logger.debug("debug2")(DebugFilter.All)
      logger.trace(ex2)

    } { (outMsgs, errMsgs) =>
      assertEquals(6, outMsgs.length.toLong)
      assertEquals(8, errMsgs.length.toLong)

      assert(isWarn(outMsgs(0)) && outMsgs(0).endsWith("warn0"))
      assert(isInfo(outMsgs(1)) && outMsgs(1).endsWith("info0"))
      assert(isWarn(outMsgs(2)) && outMsgs(2).endsWith("warn1"))
      assert(isInfo(outMsgs(3)) && outMsgs(3).endsWith("info1"))
      assert(isWarn(outMsgs(4)) && outMsgs(4).endsWith("warn2"))
      assert(isInfo(outMsgs(5)) && outMsgs(5).endsWith("info2"))

      assert(isError(errMsgs(0)) && errMsgs(0).endsWith("error0"))
      assert(isError(errMsgs(1)) && errMsgs(1).endsWith("error1"))
      assert(isDebug(errMsgs(2)) && errMsgs(2).endsWith("debug1"))
      assert(isTrace(errMsgs(3)) && errMsgs(3).endsWith("java.lang.Exception: trace1"))
      assert(isError(errMsgs(7)) && errMsgs(7).endsWith("error2"))
    }

  @Test
  def multipleLoggersDontStepOnEachOtherToes(): Unit = {
    val bos0 = new ByteArrayOutputStream
    val ps0 = new PrintStream(bos0)

    val bos1 = new ByteArrayOutputStream
    val ps1 = new PrintStream(bos1)

    val l0 = BloopLogger.at("l0", ps0, ps0, false, DebugFilter.All)
    val l1 = BloopLogger.at("l1", ps1, ps1, false, DebugFilter.All)

    l0.info("info0")
    l1.info("info1")

    val msgs0 = convertAndReadAllFrom(bos0)
    val msgs1 = convertAndReadAllFrom(bos1)

    assertEquals(1, msgs0.length.toLong)
    assertEquals(1, msgs1.length.toLong)
    assertEquals("info0", msgs0.head)
    assertEquals("info1", msgs1.head)
  }

  @Test
  def multipleLoggerSameNamesDifferentOutputs(): Unit = {
    val loggerName = "same-name-logger"

    val bos0 = new ByteArrayOutputStream
    val ps0 = new PrintStream(bos0)
    val l0 = BloopLogger.at(loggerName, ps0, ps0, false, DebugFilter.All)
    l0.info("info0")

    val bos1 = new ByteArrayOutputStream
    val ps1 = new PrintStream(bos1)
    val l1 = BloopLogger.at(loggerName, ps1, ps1, false, DebugFilter.All)
    l1.info("info1")

    val msgs0 = convertAndReadAllFrom(bos0)
    val msgs1 = convertAndReadAllFrom(bos1)

    assertEquals(1, msgs0.length.toLong)
    assertEquals(1, msgs1.length.toLong)
    assertEquals("info0", msgs0.head)
    assertEquals("info1", msgs1.head)
  }

  @Test
  def isVerbose(): Unit = {
    val expectedMessage = "this-is-logged"
    runAndCheck { logger =>
      logger.debug("this-is-not-logged")(DebugFilter.All)
      assertFalse("The logger shouldn't report being in verbose mode.", logger.isVerbose)

      val verboseLogger = logger.asVerbose
      verboseLogger.debug(expectedMessage)(DebugFilter.All)
      assertTrue("The logger should report being in verbose mode.", verboseLogger.isVerbose)
    } { (outMsgs, errMsgs) =>
      assertTrue("Nothing should have been logged to stdout.", outMsgs.isEmpty)
      assertEquals(1, errMsgs.length.toLong)
      assertTrue("Logged message should have debug level.", isDebug(errMsgs(0)))
      assertTrue(
        s"Logged message should contain '$expectedMessage'",
        errMsgs(0).contains(expectedMessage)
      )
    }
  }

  @Test
  def isVerboseForConcreteContexts(): Unit = {
    val AllDebugLog = "This is an all debug log"
    val CompilationDebugLog = "This is a compilation debug log"
    val CompilationDebugLog2 = "This is a compilation debug log 2"

    runAndCheck { logger0 =>
      val logger = logger0.asVerbose
      logger.debug(AllDebugLog)(DebugFilter.All)
      logger.debug("This is a bsp log")(DebugFilter.Bsp)
      logger.debug("This is a file watching log")(DebugFilter.FileWatching)
      logger.debug("This is a test log")(DebugFilter.Test)
      logger.debug(CompilationDebugLog)(DebugFilter.Compilation)

      // Use a locally scope to force the use of an implicit
      locally {
        implicit val ctx: DebugFilter = DebugFilter.Compilation
        logger.debug(CompilationDebugLog2)
      }
    } { (_, stderr) =>
      val successfulDebugs = List(AllDebugLog, CompilationDebugLog, CompilationDebugLog2)
      val prefixedLogs = successfulDebugs.map(msg => s"[D] $msg").sorted
      assertTrue(s"Unexpected ${stderr}", stderr.toList.sorted == prefixedLogs)
    }(DebugFilter.Compilation)
  }

  @Test
  def colorsAreKeptWhenEnabled(): Unit = {
    val plainMessage = s"this is an info message"
    val plainErrorPrefix = "[E] "
    val coloredMessage = plainMessage.replaceAll("info", s"${RED}info${RESET}")
    runAndCheck { _.info(coloredMessage) } { (outMsgs, _) =>
      assertEquals(1, outMsgs.length.toLong)
      assertEquals(coloredMessage, outMsgs.head)
    }(enableColors = true)

    runAndCheck { _.error(coloredMessage) } { (_, errMsgs) =>
      assertEquals(1, errMsgs.length.toLong)
      // Color should have been removed from the message
      assertEquals(plainErrorPrefix + plainMessage, errMsgs.head)
    }(enableColors = false)
  }

  private def isWarn(msg: String): Boolean = msg.contains("[W]")
  private def isError(msg: String): Boolean = msg.contains("[E]")
  private def isDebug(msg: String): Boolean = msg.contains("[D]")
  private def isTrace(msg: String): Boolean = msg.contains("[T]")
  private def isInfo(msg: String): Boolean = {
    !(isWarn(msg) || isError(msg) || isDebug(msg) || isTrace(msg))
  }

  private def runAndCheck(
      op: BloopLogger => Unit
  )(
      check: (Seq[String], Seq[String]) => Unit
  )(implicit ctx: DebugFilter = DebugFilter.All, enableColors: Boolean = false): Unit = {
    val outStream = new ByteArrayOutputStream
    val errStream = new ByteArrayOutputStream

    val out = new PrintStream(outStream)
    val err = new PrintStream(errStream)

    val loggerName = UUIDUtil.randomUUID
    val logger = BloopLogger.at(loggerName, out, err, enableColors, ctx)
    op(logger)

    val outMessages = convertAndReadAllFrom(outStream)
    val errMessages = convertAndReadAllFrom(errStream)

    check(outMessages, errMessages)
  }

  private def convertAndReadAllFrom(stream: ByteArrayOutputStream): Seq[String] = {
    val inStream = new ByteArrayInputStream(stream.toByteArray)
    val reader = new BufferedReader(new InputStreamReader(inStream))

    val buffer = mutable.Buffer.empty[String]
    var current = ""
    while ({ current = reader.readLine(); current } != null) {
      buffer += current
    }

    buffer.toSeq
  }
}
