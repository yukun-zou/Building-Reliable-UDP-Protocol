import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

public class GUDPSocket_refer implements GUDPSocketAPI {
    DatagramSocket datagramSocket;
    private static final Random random= new Random();
    private final Map<String, ReceiveCache> receiveCache = new ConcurrentHashMap<String, ReceiveCache>();
    private final Map<String, PriorityBlockingQueue<WindowItem>> windowCache= new ConcurrentHashMap<>();
    private final static long TIMEOUT=3*1000;
    private final Thread ackThread=new Thread(new Runnable() {
        @Override
        public void run() {
            while (!Thread.interrupted()){
                byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
                DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
                try {
                    datagramSocket.receive(udppacket);
                    GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);
                    if (gudppacket.getType()==GUDPPacket.TYPE_ACK){
                        String key = getKey(gudppacket.getSocketAddress());
                        PriorityBlockingQueue<WindowItem> window = windowCache.get(key);
                        if (window==null){
                            System.err.println("undefine window: "+key);
                        }else {
                            System.out.println("useful window: "+key);
                            for(WindowItem item:window){
                                if (item.packet.getSeqno()+1==gudppacket.getSeqno()){
                                    window.remove(item);
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
    private final Map<String, ArrayList<GUDPPacket>> sendCache=new ConcurrentHashMap<>();

    private String getKey(SocketAddress socketAddress) {
        int index = socketAddress.toString().lastIndexOf('/');
        return socketAddress.toString().substring(index);
    }

    // retry
    private final Thread retryThread=new Thread(new Runnable() {
        @Override
        public void run() {
            while (!Thread.interrupted()) {
                WindowItem next = null;
                PriorityBlockingQueue<WindowItem> windowNext=null;
                for (PriorityBlockingQueue<WindowItem> window : windowCache.values()) {
                    if (window.size() == 0) {
                        continue;
                    }
                    if (next == null) {
                        next = window.peek();
                        windowNext=window;
                    } else if (next.time > window.peek().time) {
                        next = window.peek();
                        windowNext=window;
                    }
                }
                if (next==null){
                    try {
                        Thread.sleep(TIMEOUT);
                    } catch (InterruptedException e) {
                    }
                    continue;
                }
                long diff = System.currentTimeMillis() - next.time;
                if (diff > TIMEOUT) {
                    try {
                        windowNext.poll();
                        System.out.println("send(retry):"+next.packet.getSeqno());
                        datagramSocket.send(next.packet.pack());
                        // add again.
                        next.time=System.currentTimeMillis();
                        windowNext.put(next);

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
    public GUDPSocket_refer(DatagramSocket socket) {
        datagramSocket = socket;
    }

    public void send(DatagramPacket packet) throws IOException {
        ArrayList<GUDPPacket> cache = sendCache.get(getKey(packet.getSocketAddress()));
        if (cache==null){
            cache=new ArrayList<>();
            // add BSN
            GUDPPacket bsn = new GUDPPacket(ByteBuffer.allocate(GUDPPacket.HEADER_SIZE));
            bsn.setPayload(new byte[0]);
            bsn.setType(GUDPPacket.TYPE_BSN);
            bsn.setSocketAddress((InetSocketAddress) packet.getSocketAddress());
            bsn.setSeqno(random.nextInt());
            cache.add(bsn);
            sendCache.put(getKey(packet.getSocketAddress()),cache);
        }
        int seq=cache.get(cache.size()-1).getSeqno()+1;
        GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);
        gudppacket.setSeqno(seq);
        cache.add(gudppacket);
    }

    public void receive(DatagramPacket packet) throws IOException {
        // first,return cached data.
        for (Map.Entry<String, ReceiveCache> entry : receiveCache.entrySet()) {
            DatagramPacket data = entry.getValue().next();
            if (data != null) {
                packet.setData(data.getData());
                packet.setSocketAddress(data.getSocketAddress());
                packet.setLength(data.getLength());
                return;
            }
        }
        // receive data
        byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
        DatagramPacket udppacket = new DatagramPacket(buf, buf.length);
        datagramSocket.receive(udppacket);
        GUDPPacket gudppacket = GUDPPacket.unpack(udppacket);
        gudppacket.decapsulate(packet);
        receiveAck(gudppacket);
        if (gudppacket.getType() == GUDPPacket.TYPE_BSN) {
            String key = getKey(gudppacket.getSocketAddress());
            receiveCache.put(key, new ReceiveCache(gudppacket.getSeqno()));
        } else if (gudppacket.getType() == GUDPPacket.TYPE_DATA) {
            ReceiveCache cache=receiveCache.get(getKey(gudppacket.getSocketAddress()));
            if(cache==null){
                throw new IOException("must receive BSN first."+getKey(gudppacket.getSocketAddress()));
            }
            DatagramPacket pack = new DatagramPacket(new byte[GUDPPacket.MAX_DATAGRAM_LEN], GUDPPacket.MAX_DATAGRAM_LEN);
            gudppacket.decapsulate(pack);
            cache.add(gudppacket.getSeqno(), pack);
        }
        // try again
        receive(packet);
    }

    private void receiveAck(GUDPPacket gudppacket) throws IOException {
        GUDPPacket ack = new GUDPPacket(ByteBuffer.allocate(GUDPPacket.HEADER_SIZE));
        ack.setPayload(new byte[0]);
        ack.setSocketAddress(gudppacket.getSocketAddress());
        ack.setType(GUDPPacket.TYPE_ACK);
        ack.setSeqno(gudppacket.getSeqno() + 1);
        System.out.println("send,ACK:" + ack.getSeqno());
        datagramSocket.send(ack.pack());
    }

    public void finish() throws IOException {
        if(!ackThread.isAlive()){
            ackThread.start();
        }
        if(!retryThread.isAlive()){
            retryThread.start();
        }
        while (sendCache.size()>0){
            for(List<GUDPPacket> sendList:sendCache.values()){
                GUDPPacket it=sendList.get(0);
                PriorityBlockingQueue<WindowItem> window = windowCache.get(getKey(it.getSocketAddress()));
                if(window==null){

                    System.out.println("add window:"+getKey(it.getSocketAddress()));
                  window=new PriorityBlockingQueue<>();
                  windowCache.put(getKey(it.getSocketAddress()),window);
                }
                if (window.size()>=3){
                    // window is full.
                    continue;
                }
                sendList.remove(0);
                window.add(new WindowItem(it));
                System.out.println("send:"+it.getSeqno());
                datagramSocket.send(it.pack());
                if(sendList.size()==0){
                    sendCache.remove(getKey(it.getSocketAddress()));
                }
            }
        }
        // wait until window is null
        while (true){
            boolean flag= false;
            for (PriorityBlockingQueue<WindowItem> items:windowCache.values()){
                if (items.size()>0){
                    flag=true;
                    break;
                }
            }
            if (!flag){
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
        }
    }

    public void close() throws IOException {
        System.out.println("close");
        if (datagramSocket!=null) {
            datagramSocket.close();
        }
        ackThread.interrupt();
        retryThread.interrupt();
    }

    private static class ReceiveCache {
        int curReq;
        final ConcurrentHashMap<Integer, DatagramPacket> cache;

        public ReceiveCache(int startSeq) {
            this.curReq = startSeq+1;
            cache = new ConcurrentHashMap<>();
        }
        public void add(int seq,DatagramPacket data){
            cache.put(seq,data);
        }
        public DatagramPacket next() {
            DatagramPacket cur = cache.get(curReq);
            if (cur == null) {
                return null;
            }
            cache.remove(curReq);
            curReq++;
            return cur;
        }
    }
    private static class WindowItem implements Comparable<WindowItem>{
        private long time;
        private GUDPPacket packet;

        public WindowItem(GUDPPacket packet) {
            this.packet = packet;
            this.time=System.currentTimeMillis();
        }

        @Override
        public int compareTo(WindowItem o) {
            return (int) (this.time-o.time);
        }
    }


}

