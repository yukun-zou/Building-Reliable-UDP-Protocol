import java.net.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.Semaphore;
//definition of buffer
class GUDPBuffer{


    private LinkedList<GUDPPacket>windowList = new LinkedList<>();
    private InetSocketAddress endpoint;

    public static final short MAX_RETRY=5;
    public static final long TIMEOUT=3000;

    private int win = GUDPPacket.MAX_WINDOW_SIZE;
    private int seq = 0;
    private int ack = 0;
    private short retry = 0;
    private long time = 0;

    public GUDPBuffer(InetSocketAddress inetSocketAddress) {endpoint = inetSocketAddress;}
    public InetSocketAddress getEndpoint() {return endpoint;}
    public int getwin() {
        return win;
    }
    public int getseq() {
        return seq;
    }
    public int getack() {
        return ack;
    }
    public short getretry() {
        return retry;
    }
    public long gettime() {
        return time;
    }
    public LinkedList<GUDPPacket> getwindowList() {
        return windowList;
    }

    public void setwin(int w) {
        win = w;
    }
    public void setseq(int s) {
        seq = s;
    }
    public void setack(int a) {
        ack = a;
    }
    public void setretry(short r) {retry = r;}
    public void settimeout(long t) {time = t;}

    public void add(GUDPPacket g){
        windowList.addLast(g);
    }
    public GUDPPacket remove(){
        return windowList.remove(0);
    }

}

public class GUDPSocket_kun implements GUDPSocketAPI {
    DatagramSocket datagramSocket;
    //sendwindowlist and receivewindowlist used for sending multiple files to multiple destinations
    protected LinkedList<GUDPBuffer> sendwindowList = new LinkedList<>();
    protected LinkedList<GUDPBuffer> receivewindowList = new LinkedList<>();
    protected LinkedList<GUDPBuffer> slide_window = new LinkedList<>();
    private final Map<String, PriorityBlockingQueue<Time_Object>> queue_window = new ConcurrentHashMap<>();

    private String getKey(SocketAddress socketAddress) {
        int index = socketAddress.toString().lastIndexOf('/');
        return socketAddress.toString().substring(index);
    }

    //SendThread sen;
    //RetransmitThread r;
    private boolean debug = true;
    static int timeoutVal = 3000;        // 300ms until timeout
    Semaphore s;                // guard CS for base, nextSeqNum
    Timer timer;                // for timeouts
    private boolean finish = false;

    public void setfinish(boolean f) {
        finish = f;
    }

    public boolean getfinish() {
        return finish;
    }

    public void setTimer(boolean isNewTimer) {
        if (timer != null) timer.cancel();
        if (isNewTimer) {
            timer = new Timer();
            //timer.schedule(new Timeout(), timeoutVal);
        }
    }

    // Timeout task
    public class Timeout extends TimerTask {
        DatagramPacket packet;

        public void run() {
            try {
                s.acquire();    /***** enter CS *****/
                System.out.println("Sender: Timeout!");
                //retransmit
                GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);
                DatagramPacket udpdata = gudppacket.pack();
                datagramSocket.send(udpdata);
                s.release();    /***** leave CS *****/
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }//handle unexpected situations
        }
    }// END CLASS Timeout

    public int endpointInWindowList(InetAddress addr, int port, LinkedList<GUDPBuffer> list) {
        int i = 0;
        while (i < list.size()) {
            if ((list.get(i).getEndpoint().getAddress().equals(addr)) && (list.get(i).getEndpoint().getPort() == port)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    public GUDPSocket(DatagramSocket socket) {
        datagramSocket = socket;
    }

    public void send(DatagramPacket packet) throws IOException {
        int index = this.endpointInWindowList(packet.getAddress(), packet.getPort(), this.sendwindowList);
        InetSocketAddress ep = new InetSocketAddress(packet.getAddress(), packet.getPort());
        if (index < 0) {
            //create new GUDPBuffer for this endpoint
            GUDPBuffer gBuffer = new GUDPBuffer(ep);
            if (debug) {
                System.out.println("new buffer for: " + String.valueOf(ep.getAddress()) + " " + String.valueOf(ep.getPort()));
            }
            ByteBuffer buf = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
            buf.order(ByteOrder.BIG_ENDIAN);
            GUDPPacket gpack = new GUDPPacket(buf);
            gpack.setSocketAddress(ep);
            gpack.setVersion(GUDPPacket.GUDP_VERSION);
            gpack.setType(GUDPPacket.TYPE_BSN);
            Random rand = new Random();
            int rnd = rand.nextInt();
            gpack.setSeqno(rnd);
            gpack.setPayload(new byte[0]);
            gBuffer.add(gpack);
            gBuffer.setseq(rnd);
            //gBuffer.setack(rnd + 1);
            sendwindowList.add(gBuffer);
            index = sendwindowList.indexOf(gBuffer);
            if (debug) {
                System.out.println(index);
                System.out.println(gpack.getSeqno());
            }

        }
        GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);
        gudppacket.setSeqno(sendwindowList.get(index).getseq() + 1);
        sendwindowList.get(index).setseq(sendwindowList.get(index).getseq() + 1);
        sendwindowList.get(index).add(gudppacket);
    }

    public void receive(DatagramPacket packet) throws IOException {
        // receive data
        byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
        DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
        datagramSocket.receive(udppacket);
        GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);
        int index = this.endpointInWindowList(udppacket.getAddress(), udppacket.getPort(), this.receivewindowList);
        InetSocketAddress ep = new InetSocketAddress(udppacket.getAddress(), udppacket.getPort());
        if (gudppacket.getType() == GUDPPacket.TYPE_BSN) {
            GUDPBuffer gBuffer = new GUDPBuffer(ep);
            receivewindowList.add(gBuffer);
            index = receivewindowList.indexOf(gBuffer);
            receivewindowList.get(index).setseq(receivewindowList.get(index).getseq() + 1);
        } else if (gudppacket.getType() == GUDPPacket.TYPE_DATA) {
            if(index<0){
                throw new IOException("must receive BSN first."+getKey(gudppacket.getSocketAddress()));
            }
            if(gudppacket.getSeqno()==receivewindowList.get(index).getseq()) {
                DatagramPacket pack = new DatagramPacket(new byte[GUDPPacket.MAX_DATAGRAM_LEN], GUDPPacket.MAX_DATAGRAM_LEN);
                gudppacket.decapsulate(pack);
                receivewindowList.get(index).add(gudppacket);
                receivewindowList.get(index).setseq(receivewindowList.get(index).getseq() + 1);
            }
        }
        //ack
        GUDPPacket udp_ack = new GUDPPacket(ByteBuffer.allocate(GUDPPacket.HEADER_SIZE));
        udp_ack.setPayload(new byte[0]);
        udp_ack.setSocketAddress(gudppacket.getSocketAddress());
        udp_ack.setType(GUDPPacket.TYPE_ACK);
        udp_ack.setSeqno(gudppacket.getSeqno()+1);
        System.out.println("send,ACK:" + udp_ack.getSeqno());
        datagramSocket.send(udp_ack.pack());
        //receivewindowList.get(index).remove();
        // try again
        receive(packet);
    }

    public void finish() throws IOException {
        if (!SendThread.isAlive()) {
            SendThread.start();
        }
        if (!RetransmitThread.isAlive()) {
            RetransmitThread.start();
        }

        for (int i = 0; i < sendwindowList.size(); i++) {
            GUDPBuffer gbuffer = sendwindowList.get(i);
            while (gbuffer.getwindowList().size() > 0) {
                GUDPPacket it = gbuffer.getwindowList().get(0);
                PriorityBlockingQueue<Time_Object> window = queue_window.get(getKey(it.getSocketAddress()));
                DatagramPacket it_udp = it.pack();
                int index = this.endpointInWindowList(it_udp.getAddress(), it_udp.getPort(), this.slide_window);
                InetSocketAddress ep = new InetSocketAddress(it_udp.getAddress(), it_udp.getPort());
                if (index < 0) {
                    System.out.println("add window:" + String.valueOf(ep.getAddress()) + " " + String.valueOf(ep.getPort()));
                    GUDPBuffer gBuffer = new GUDPBuffer(ep);
                    slide_window.add(gBuffer);
                    index = slide_window.indexOf(gBuffer);
                    window = new PriorityBlockingQueue<>();
                    queue_window.put(getKey(it.getSocketAddress()), window);
                }
                if (slide_window.size() >= 3) {
                    continue;
                }
                GUDPPacket y = gbuffer.remove();
                slide_window.get(index).add(it);
                System.out.println("send:" + it.getSeqno());
                datagramSocket.send(it.pack());
                window.add(new Time_Object(it));
            }
            if(gbuffer.getwindowList().size()==0){
                GUDPBuffer x = sendwindowList.remove(i);
            }
        }
        while (true) {
            boolean flag = false;
            for (PriorityBlockingQueue<Time_Object> items : queue_window.values()) {
                if (items.size() > 0) {
                    flag = true;
                    break;
                }
            }
            if (!flag) {
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
        }
    }

        public void close () throws IOException {
            System.out.println("close");
            if (datagramSocket != null) {
                datagramSocket.close();
            }
            SendThread.interrupt();
            RetransmitThread.interrupt();
        }
        private final Thread SendThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
                    DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
                    try {
                        datagramSocket.receive(udppacket);
                        int index = endpointInWindowList(udppacket.getAddress(), udppacket.getPort(), slide_window);
                        InetSocketAddress ep = new InetSocketAddress(udppacket.getAddress(), udppacket.getPort());
                        GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);
                        if (gudppacket.getType() == GUDPPacket.TYPE_ACK) {
                            String key = getKey(gudppacket.getSocketAddress());
                            PriorityBlockingQueue<Time_Object> window = queue_window.get(key);
                            if (index < 0) {
                                System.err.println("undefine window: " + String.valueOf(ep.getAddress()) + " " + String.valueOf(ep.getPort()));
                            } else {
                                System.out.println("useful window: " + String.valueOf(ep.getAddress()) + " " + String.valueOf(ep.getPort()));
                                for(Time_Object item:window){
                                    if (item.packet.getSeqno()+1==gudppacket.getSeqno()){
                                        window.remove(item);
                                        break;
                                    }
                                }
                                for (int i = 0; i < slide_window.get(index).getwindowList().size(); i++) {
                                    if (slide_window.get(index).getwindowList().get(i).getSeqno() + 1 == gudppacket.getSeqno()) {
                                        slide_window.get(index).getwindowList().remove(i);
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                    }
                }
            }
        });

        private final Thread RetransmitThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!Thread.interrupted()) {
                    Time_Object object = null;
                    PriorityBlockingQueue<Time_Object> queue_object = null;
                    for (PriorityBlockingQueue<Time_Object> queue : queue_window.values()) {
                        if (queue.size() == 0) {
                            continue;
                        }
                        if (object == null) {
                            object = queue.peek();
                            queue_object = queue;
                        } else if (object.time > queue.peek().time) {
                            object = queue.peek();
                            queue_object = queue;
                        }
                    }
                    if (object == null) {
                        try {
                            Thread.sleep(GUDPBuffer.TIMEOUT);
                        } catch (InterruptedException e) {
                        }
                        continue;
                    }
                    long diff = System.currentTimeMillis() - object.time;
                    if (diff > GUDPBuffer.TIMEOUT) {
                        try {
                            queue_object.poll();
                            System.out.println("send(retry):" + object.packet.getSeqno());
                            datagramSocket.send(object.packet.pack());
                            // add again.
                            object.time = System.currentTimeMillis();
                            queue_object.put(object);

                        } catch (IOException e) {
                        }
                    } else {
                        try {
                            Thread.sleep(3000 - diff);
                        } catch (InterruptedException e) {
                        }
                    }
                }
            }
        });
    private static class Time_Object implements Comparable<Time_Object>{
        private long time;
        private GUDPPacket packet;

        public Time_Object(GUDPPacket packet) {
            this.packet = packet;
            this.time=System.currentTimeMillis();
        }

        @Override
        public int compareTo(Time_Object o) {
            return (int) (this.time-o.time);
        }
    }
}