package spray.can.server.websockets

import akka.util.ByteString
import java.nio.BufferUnderflowException
import java.io.{OutputStream, InputStream}

/**
 * A very fast circular, growable read-write byte buffer.
 */
class UberBuffer(initSize: Int = 32){ self =>
  var data = new Array[Byte](initSize)
  var readPos = 0
  var writePos = 0
  
  def capacity = data.length
  object inputStream extends InputStream{
    var mark = 0
    def read(): Int = {
      val x = self.read().toInt
      if (x < 0) x + 256 else x
    }
    override def available() = readAvailable
    override def read(b: Array[Byte], offset: Int, length: Int) = readTo(b, offset, length)
    override def skip(n: Long) = {
      readPos = incr(readPos, n)
      n
    }
    override def mark(n: Int) = mark = readPos
    override def reset() = readPos = mark

  }
  object outputStream extends OutputStream{
    def write(b: Int) = self.write(ByteString(b.toByte))
    override def write(b: Array[Byte], offset: Int, length: Int) = {
      while (writeAvailable < length) expand()

      val left = Math.min(data.length - writePos, length)
      val right = Math.max(length - (data.length - writePos), 0)

      System.arraycopy(b, writePos, data, offset, left)
      System.arraycopy(b, writePos, data, offset + left, right)

      writePos = incr(writePos, length)
    }

  }

  def readAvailable = {
    if (writePos == readPos){
      0
    }else if(writePos > readPos){
      //   1 2 3
      // - - - - -
      //   r   W
      //       R
      writePos - readPos
    } else {
      // 3 4   1 2
      // - - - - -
      //   W   r
      //   Rs
      data.length - readPos + writePos
    }
  }
  def writeAvailable = {
    if (writePos == readPos){
      data.length - 1
    }else if (writePos > readPos){
      //    1 2 3 4
      //  - - - - -
      //  W R w
      data.length - writePos - 1 + readPos
    }else{
      //    1
      //  - - - - -
      //    w W R
      readPos - writePos - 1
    }
  }

  def expand() = {
    val newData= new Array[Byte](data.length * 2)

    if (readPos <= writePos){
      System.arraycopy(data, readPos, newData, 0, writePos - readPos)
      writePos = writePos - readPos
    }else{
      System.arraycopy(data, readPos, newData, 0, data.length - readPos)
      System.arraycopy(data, 0, newData, data.length - readPos, writePos)
      writePos = writePos + data.length - readPos
    }
    readPos = 0

    data = newData
  }

  def write(in: ByteString, offset: Int = 0, length0: Int = -1) = {
    while (writeAvailable < in.length) expand()

    val (left, right) = in.splitAt(data.length - writePos)

    left.copyToArray(data, writePos)
    right.copyToArray(data, 0)

    writePos = incr(writePos, in.length)
  }

  def read() = {
    if (1 > readAvailable) throw new BufferUnderflowException
    val out = data(readPos)
    readPos = incr(readPos, 1)
    out
  }

  def readTo(target: Array[Byte], index: Int, n: Int) = {
    if (n > readAvailable) throw new BufferUnderflowException
    val left = Math.min(data.length - readPos, n)
    val right = Math.max(n - (data.length - readPos), 0)
    System.arraycopy(data, readPos, target, index, left)
    System.arraycopy(data, 0, target, index + left, right)
    readPos = incr(readPos, n)
    n
  }

  private[this] def incr(n: Int, d: Long) = {
    ((n + d) % data.length).toInt
  }
}
