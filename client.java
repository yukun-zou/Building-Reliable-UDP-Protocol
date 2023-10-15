import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class client     {
    public static void main(String[] args) throws Exception {
        DatagramSocket socket = null;
        try {
            //封装发送的数据
            String msg = "testing 12 test 34";
            byte[] data = msg.getBytes();
            //指定发送方IP地址
            InetAddress inetAddress = InetAddress.getByName("127.0.0.1");
            //封装数据报
            DatagramPacket datagramPacket = new DatagramPacket(data, 0, data.length, inetAddress, 9001);
            socket = new DatagramSocket();
            //发送数据报
            GUDPSocket_refer gudpSocket = new GUDPSocket_refer(socket);
            gudpSocket.send(datagramPacket);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }
}
