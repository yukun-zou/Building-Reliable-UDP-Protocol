import java.net.DatagramPacket;
import java.net.DatagramSocket;

public class server {
    public static void main(String[] args) throws Exception {
        DatagramSocket socket = null;
        try {
            //
            socket = new DatagramSocket(9001);
            //
            byte[] data = new byte[1024];
            DatagramPacket datagramPacket = new DatagramPacket(data, 0, data.length);
            //
            GUDPSocket_refer gudpSocket = new GUDPSocket_refer(socket);
            gudpSocket.receive(datagramPacket);
            String msg = new String(datagramPacket.getData(), 0, datagramPacket.getLength());
            System.out.println("receive " + msg);

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
}
}
