package spray.can.server.websockets

import akka.util.ByteString
import java.nio.BufferUnderflowException
import java.io.InputStream

/**
 * A very fast circular, growable read-write byte buffer.
 */
class UberBuffer(initSize: Int = 1024) extends InputStream{
  var data = new Array[Byte](initSize)
  var readPos = 0
  var writePos = 0

  def capacity = data.length

  override def available = {
    if (writePos == readPos){
      0
    }else if(writePos > readPos){
      //   1 2 3
      // - - - - -
      //   r   W
      //       R
      writePos - readPos + 1
    } else {
      // 3 4   1 2
      // - - - - -
      //   W   r
      //   Rs
      data.length - readPos + writePos + 1
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

  def write(in: ByteString) = {
    while (writeAvailable < in.length) {
      expand()
    }

    val (left, right) = in.splitAt(data.length - writePos)


    left.copyToArray(data, writePos)
    right.copyToArray(data, 0)
    writePos = incr(writePos, in.length)
  }

  def readByte() = {
    if (1 > available) throw new BufferUnderflowException
    val out = data(readPos)
    readPos = incr(readPos, 1)
    out
  }

  override def read(target: Array[Byte], index: Int, n: Int) = {
    if (n > available) throw new BufferUnderflowException
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

  def read(): Int = readByte()

  override def skip(n: Long) = {
    readPos = incr(readPos, n)
    n
  }
}
