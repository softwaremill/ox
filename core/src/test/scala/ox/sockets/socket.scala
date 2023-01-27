package ox.sockets

trait Socket:
  def accept(timeout: Long): ConnectedSocket

trait ConnectedSocket:
  def send(msg: String): Unit
  def receive(timeout: Long): String

class SocketTerminatedException extends Exception
