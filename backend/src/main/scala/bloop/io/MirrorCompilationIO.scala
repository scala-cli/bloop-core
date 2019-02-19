package bloop.io

import bloop.logging.Logger

import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  FileVisitOption,
  FileVisitResult,
  FileVisitor,
  Files,
  Path,
  SimpleFileVisitor,
  StandardCopyOption
}

import scala.util.control.NonFatal
import scala.collection.JavaConverters._

import monix.eval.Task
import monix.execution.{Scheduler, Cancelable}
import monix.reactive.{Observable, Consumer, Observer}
import monix.reactive.MulticastStrategy

import io.methvin.watcher.DirectoryChangeEvent.EventType
import io.methvin.watcher.{DirectoryChangeEvent, DirectoryChangeListener, DirectoryWatcher}

object MirrorCompilationIO {
  def apply(
      origin: Path,
      target: Path,
      scheduler: Scheduler,
      executor: Executor,
      logger0: Logger
  ): (Task[Unit], Cancelable) = {
    // Make sure that the target is the real path and not a symlink
    var watchingEnabled: Boolean = true
    val logger = new bloop.logging.Slf4jAdapter(logger0)

    val (observer, observable) =
      Observable.multicast[DirectoryChangeEvent](MulticastStrategy.publish)(scheduler)

    val listener = new DirectoryChangeListener {
      override def isWatching: Boolean = watchingEnabled
      override def onException(e: Exception): Unit = ()
      override def onEvent(event: DirectoryChangeEvent): Unit = {
        observer.onNext(event)
        ()
      }
    }

    val watcher = DirectoryWatcher
      .builder()
      .paths(List(origin).asJava)
      .listener(listener)
      .fileHashing(false)
      .build()

    val watcherHandle = watcher.watchAsync(executor)
    val watchCancellation = Cancelable { () =>
      watchingEnabled = false
      watcherHandle.complete(null)

      import java.util.concurrent.TimeUnit
      // Give some time to process some events before closing
      scheduler.scheduleOnce(10, TimeUnit.MILLISECONDS, new Runnable {
        def run(): Unit = watcher.close()
      })

      observer.onComplete()
      logger.debug("Cancelling file watcher")
    }

    //val consumeTask = observable.consumeWith(fileEventConsumer(origin, target, logger0))
    val copyEverything = copyDirectories(origin, target, 5)(scheduler)
    //consumeTask.flatMap(_ => copyEverything) -> watchCancellation
    copyEverything -> watchCancellation
  }

  def copyDirectories(origin: Path, target: Path, parallelUnits: Int)(
      scheduler: Scheduler
  ): Task[Unit] = {
    val (observer, observable) = Observable.multicast[(Path, Path)](
      MulticastStrategy.publish
    )(scheduler)

    val discovery = new FileVisitor[Path] {
      var stop: Boolean = false
      var currentTargetDirectory: Path = target
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (attributes.isDirectory) FileVisitResult.CONTINUE
        else {
          val stop = observer.onNext((file, currentTargetDirectory.resolve(file.getFileName))) == monix.execution.Ack.Stop
          if (stop) FileVisitResult.TERMINATE
          else FileVisitResult.CONTINUE
        }
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = FileVisitResult.CONTINUE

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = {
        currentTargetDirectory = currentTargetDirectory.resolve(directory.getFileName)
        Files.createDirectory(currentTargetDirectory)
        FileVisitResult.CONTINUE
      }

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = {
        currentTargetDirectory = currentTargetDirectory.getParent()
        FileVisitResult.CONTINUE
      }
    }

    val discoverFileTree = Task {
      Files.walkFileTree(origin, discovery)
      ()
    }.doOnFinish {
      case Some(t) => Task(observer.onError(t))
      case None => Task(observer.onComplete())
    }

    val copyFilesInParallel = observable.consumeWith(Consumer.foreachParallelAsync(parallelUnits) {
      case (originFile, targetFile) =>
        Task {
          //println(s"Copying ${origin.relativize(originFile)}")
          Files.copy(
            originFile,
            targetFile,
            StandardCopyOption.COPY_ATTRIBUTES,
            StandardCopyOption.REPLACE_EXISTING
          )
          ()
        }
    })

    Task.gatherUnordered(List(discoverFileTree, copyFilesInParallel)).map(_ => ())
  }

  import scala.util.Try
  private def fileEventConsumer(
      origin: Path,
      target: Path,
      logger: Logger
  ): Consumer[DirectoryChangeEvent, Unit] = {
    val dirAttrs = new ConcurrentHashMap[Path, Boolean]()
    val pathsWithIOErrors = ConcurrentHashMap.newKeySet[Path]()
    val pathsFailedToProcess = ConcurrentHashMap.newKeySet[Path]()
    Consumer.foreachParallelAsync(3) { (event: DirectoryChangeEvent) =>
      // The path may not exist yet, if it doesn't then
      val path = event.path()
      val attrsRead = Try {
        // By default, symbolic links are followed
        Files.readAttributes(path, classOf[BasicFileAttributes])
      }

      def registerIOError[T](path: Path, label: String)(effect: => T): Boolean = {
        try {
          //println(s"${origin.relativize(path)}: $label")
          effect
          true
        } catch {
          case NonFatal(t) =>
            logger.error(s"Failed ${t}")
            pathsFailedToProcess.add(path); false
        }
      }

      def createPath(path: Path, attrs: BasicFileAttributes, newPath: Path): Task[Unit] = Task {
        val dirPath = if (attrs.isDirectory) newPath else newPath.getParent
        // Create the directory if it's a dir or the parent if it's a file first
        val created = dirAttrs.computeIfAbsent(dirPath, (dirPath: Path) => {
          registerIOError(dirPath, "creating dir")(Files.createDirectories(dirPath))
        })

        // We ignore it if it's anything else than a file (symlink)
        if (created && attrs.isRegularFile) {
          registerIOError(path, "creating file") {
            Files.copy(
              path,
              newPath,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES
            )
          }
          ()
        }
      }

      def modifyPath(path: Path, attrs: BasicFileAttributes, newPath: Path): Task[Unit] = Task {
        if (attrs.isRegularFile) {
          // When a path has been modified we assume the parent already exists and copy right away
          registerIOError(path, "modifying file") {
            Files.copy(
              path,
              newPath,
              StandardCopyOption.REPLACE_EXISTING,
              StandardCopyOption.COPY_ATTRIBUTES
            )
          }
          ()
        }
      }

      def deletePath(path: Path, attrs: BasicFileAttributes, newPath: Path): Task[Unit] = Task {
        registerIOError(path, "deleting file/dir")(Files.deleteIfExists(newPath))
        ()
      }

      attrsRead match {
        case scala.util.Success(attrs) =>
          val newPath = target.resolve(origin.relativize(path))
          event.eventType match {
            case EventType.CREATE => createPath(path, attrs, newPath)
            case EventType.MODIFY => modifyPath(path, attrs, newPath)
            case EventType.OVERFLOW => Task.now(())
            case EventType.DELETE => deletePath(path, attrs, newPath)
          }
        case scala.util.Failure(e) =>
          pathsWithIOErrors.add(path)
          Task.now(())
      }
    }
  }
}
