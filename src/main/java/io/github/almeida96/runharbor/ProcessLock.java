package io.github.almeida96.runharbor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class ProcessLock implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  ProcessLock(Path database) throws IOException {
    Path path = database.toAbsolutePath().normalize();
    Files.createDirectories(path.getParent());
    // Resolve parent aliases so two spellings of a directory cannot create separate lock files.
    Path lockPath = path.getParent().toRealPath().resolve(path.getFileName() + ".lock");
    Json.require(
        !Files.isSymbolicLink(path) && !Files.isSymbolicLink(lockPath),
        "Database and lock must not be symlinks");
    channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    try {
      lock = channel.tryLock();
      if (lock == null) throw new IOException("Database is already served by another process");
    } catch (IOException | RuntimeException e) {
      channel.close();
      throw e;
    }
  }

  @Override
  public void close() throws IOException {
    lock.release();
    channel.close();
  }

  void verifyHeld() {
    Json.require(lock.isValid(), "Service database lock is not held");
  }
}
