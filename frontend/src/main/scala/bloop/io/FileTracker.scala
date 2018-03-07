package bloop.io

import java.io.{InputStream, SequenceInputStream}
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.Enumeration
import java.util.zip.{Adler32, CheckedInputStream}

/**
 * Represents the state of a directory or file in which we track changes.
 *
 * @param base          The base file or directory to track.
 * @param pattern       The pattern matching the files that must be tracked inside `base`.
 * @param modifiedTimes The last modification time of the tracked files.
 * @param contentsChecksum The checksum of all the contents of the directory.
 */
final case class FileTracker(base: AbsolutePath,
                             pattern: String,
                             modifiedTimes: List[(AbsolutePath, FileTime)],
                             contentsChecksum: Long) {

  /**
   * Inspects the directory for changes.
   *
   * @return `FileTracker.Unchanged` if the tracked files didn't change. If the last modified
   *         times have changed, this contains a new `FileTracker` with the updated `modifiedTimes`.
   *         If the tracked files have changed, `FileTracker.Changed` is returned.
   */
  def changed(): FileTracker.Status = {
    val newModifiedTimes = FileTracker.getFiles(base, pattern)
    if (newModifiedTimes == modifiedTimes) FileTracker.Unchanged(None)
    else {
      val newChecksum = FileTracker.filesChecksum(newModifiedTimes.map(_._1))
      if (newChecksum != contentsChecksum) FileTracker.Changed
      else {
        val checksum = new FileTracker(base, pattern, newModifiedTimes, newChecksum)
        FileTracker.Unchanged(Some(checksum))
      }
    }
  }
}

object FileTracker {

  /** Indicates the status of a tracked directory or file */
  sealed trait Status

  /**
   * Indicates that the content of the directory or file hasn't changed.
   *
   * @param newTracker If set, the new `FileTracker` with the updated
   *                   `lastModifiedTimes` (if the files have been touched, but
   *                   not modified).
   */
  case class Unchanged(newTracker: Option[FileTracker]) extends Status

  /** Indicates that the content of the directory has changed. */
  case object Changed extends Status

  /**
   * Creates a new `FileTracker`.
   *
   * @param base    The directory or file to track.
   * @param pattern The pattern that must be matched by the tracked files.
   */
  def apply(base: AbsolutePath, pattern: String): FileTracker = {
    val files = getFiles(base, pattern)
    val checksum = filesChecksum(files.map(_._1))
    new FileTracker(base, pattern, files, checksum)
  }

  /**
   * Returns all the tracked files inside this directory, associated with their last
   * modification time.
   *
   * @param base    The base file or directory to track.
   * @param pattern The pattern to find the files to track.
   * @return A map associating each tracked file with its last modification time.
   */
  private def getFiles(base: AbsolutePath, pattern: String): List[(AbsolutePath, FileTime)] = {
    Paths
      .getAll(base, pattern)
      .map { path =>
        path -> Files.getLastModifiedTime(path.underlying)
      }
      .toList
  }

  /**
   * Computes the checksum of `files`.
   *
   * @param files The files whose checksum must be computed.
   * @return The checksum.
   */
  private def filesChecksum(files: List[AbsolutePath]): Long = {
    val streams = new Enumeration[InputStream] {
      val streams = files.map(f => Files.newInputStream(f.underlying)).toIterator
      override def hasMoreElements(): Boolean = streams.hasNext
      override def nextElement(): InputStream = streams.next()
    }

    val checkedStream = new CheckedInputStream(new SequenceInputStream(streams), new Adler32)
    try {
      while (checkedStream.read() >= 0) {}
      checkedStream.getChecksum().getValue()
    } finally checkedStream.close()
  }
}
