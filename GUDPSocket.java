import java.io.IOException;
import java.net.SocketException;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.nio.ByteOrder;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.PriorityQueue;
import java.util.concurrent.Semaphore;

public class GUDPSocket implements GUDPSocketAPI {
    //buffer
    class GUDPBuffer {
        public static final short MAX_RETRY=5;
        final Duration TIMEOUT = Duration.ofMillis(1000);
        boolean sent = false;
        final GUDPPacket packet;
        Instant resendTime;
        boolean ackReceived;
        private int seq = 0;
        int retransmit_count = 0;

        public GUDPBuffer(GUDPPacket packet) {
            this.packet = packet;
        }
        public boolean isTimeout() {
            return sent && !ackReceived && Instant.now().isAfter(resendTime);
        }
        public void sentsymbol() {
            resendTime = Instant.now()
                    .plus(TIMEOUT)
                    .minusNanos(1);
            sent = true;
        }
        public int getseq() {
            return seq;
        }

        public void setseq(int s) {
            seq = s;
        }
    }
    private final DatagramSocket datagramSocket;
    public boolean debug = false;
    public List<Integer> ackbuffer = new ArrayList<>();
    Semaphore s;                // guard CS for base, nextSeqNum
    Timer timer;                // for timeouts
    public GUDPSender send = new GUDPSender(GUDPPacket.MAX_WINDOW_SIZE);
    public GUDPReceiver receive = new GUDPReceiver(ackbuffer);

    public Thread sender = new Thread(send, "GUDP Sender");
    public Thread receiver = new Thread(receive, "GUDP Receiver");
    public boolean IsReceive = false;

    public GUDPSocket(DatagramSocket socket) throws SocketException {
        datagramSocket = socket;
    }

    public void setTimer(boolean isNewTimer) {
        if (timer != null) timer.cancel();
        if (isNewTimer) {
            //timer = new Timer();
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

    public void send(DatagramPacket packet) throws IOException {
        send.Add_send_List(packet);
    }

    public void receive(DatagramPacket packet) throws IOException {
        if (!IsReceive) {
            receiver.start();
            IsReceive = true;
            if(debug) {
                System.out.println("Receiver Start");
            }
        }
        receive.receive(packet);
    }

    public void finish() throws IOException {
        sender.start();
        if(debug) {
            System.out.println("Sender Start");
        }
    }

    public void close() throws IOException {
        if(debug) {
            System.out.println("Close");
        }
       // if (datagramSocket != null) {
            //datagramSocket.close();
        //}
    }

    class GUDPSender implements Runnable {
        private final int WindowSize;
        public int windowsize_now;
        public int First;
        public int Last;
        public final List<GUDPBuffer> sendlist = new ArrayList<>();
        public final List<Integer> acklist = new ArrayList<>();
        public InetSocketAddress endpoint;
        public int seq;
        public int bsn_seq;
        public boolean Next = false;
        public int count = 0;
        public int acklist_index = 0;
        public double send_percent = 0;

        public GUDPSender(int dataWindowSize) throws SocketException {
            this.WindowSize = dataWindowSize;
            this.windowsize_now = this.WindowSize;
            this.Add_bsn_List();
        }

        public void Add_bsn_List() {
            bsn_seq = new Random().nextInt(Short.MAX_VALUE);
            seq = bsn_seq;
            Last = 1;
            First = 0;
            ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
            buffer.order(ByteOrder.BIG_ENDIAN);
            GUDPPacket packet = new GUDPPacket(buffer);
            packet.setVersion(GUDPPacket.GUDP_VERSION);
            packet.setType(GUDPPacket.TYPE_BSN);
            packet.setSeqno(seq);
            packet.setPayloadLength(0);
            seq++;
            sendlist.add(new GUDPBuffer(packet));
        }

        public void Add_send_List(DatagramPacket packet) throws IOException {
            endpoint = (InetSocketAddress) packet.getSocketAddress();
            GUDPPacket gudppacket = GUDPPacket.encapsulate(packet);
            gudppacket.setSeqno(seq);
            seq++;
            sendlist.add(new GUDPBuffer(gudppacket));
        }
        public void send(GUDPBuffer packet) throws IOException {
            packet.sentsymbol();
            packet.packet.setSocketAddress(endpoint);
            datagramSocket.send(packet.packet.pack());
        }
        private void sendDataPackets() throws IOException {
            int newlastIndex = Math.min(sendlist.size(), First + windowsize_now);
            for (int index = First; index < newlastIndex; index++) {
                GUDPBuffer packet = sendlist.get(index);
                if (!packet.sent) {
                    send(packet);
                }
            }
            Last = newlastIndex;
        }
        public void window_process(List<Integer> ACKBuffer) throws IOException {
            for (int i = acklist_index; i < ACKBuffer.size(); i++) {
                int index = ACKBuffer.get(i) - 1 - bsn_seq;
                GUDPBuffer packetinlist = sendlist.get(index);
                if (!packetinlist.ackReceived) {
                    packetinlist.ackReceived = true;
                    count++;
                }
                acklist_index++;
            }
            first_update();
            if (count != sendlist.size()) {
                send_percent = (double) count / sendlist.size() * 100;

                if (send_percent > 0) {
                    if(debug) {
                        System.out.println("already sent:" + String.format("%.2f", send_percent) + "%");
                    }
                }
                sendDataPackets();
            }
            if (count == sendlist.size()) {
                if(debug) {
                    System.out.println("sending completed");
                }
                finish_send();
            }

        }

        public void first_update() {
            for (int index = First; index < Last; index++) {
                GUDPBuffer block = sendlist.get(index);
                if (block.ackReceived) {
                    First++;
                    if (First == 1) {
                        if(debug) {
                            System.out.println("send data");
                        }
                    }
                    continue;
                }
                break;
            }
        }

        public void retransmit() throws IOException {
            for (int index = First; index < Last; index++) {
                GUDPBuffer packetinlist = sendlist.get(index);
                if (packetinlist.isTimeout() & packetinlist.retransmit_count < 20) {
                    send(packetinlist);
                    packetinlist.retransmit_count++;
                    if(debug) {
                        System.out.println("resend packet" + (packetinlist.packet.getSeqno() - bsn_seq) + ":" + packetinlist.retransmit_count + "times");
                    }
                }
                if (packetinlist.retransmit_count == 20) {
                    if(debug) {
                        System.out.println("sending fail,too many retry times");
                    }
                    this.finish_send();
                }
            }
        }

        public void finish_send() {
            Next = true;
            if(debug) {
                System.out.println("Sender Close");
            }
            System.exit(1);
        }

        public void run() {
            GUDPReceiver receive_ack = null;
            try {
                receive_ack = new GUDPReceiver(acklist);
            } catch (SocketException e) {
                throw new RuntimeException(e);
            }
            Thread ack_Receiver = new Thread(receive_ack, "ACK Receiver");
            ack_Receiver.start();
            if(debug) {
                System.out.println("ACKreceiver Start");
            }
            while (!Next) {
                try {
                    window_process(acklist);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                try {
                    retransmit();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

        }
    }

    class GUDPReceiver implements Runnable {
        private final Queue<GUDPPacket> receivebuffer = new PriorityQueue<>(Comparator.comparingInt(GUDPPacket::getSeqno));
        private final BlockingQueue<GUDPPacket> receive_list = new LinkedBlockingQueue<>();
        private int seq = 0;
        public List<Integer> acklist;
        private boolean IsRun = true;

        public GUDPReceiver(List<Integer> ackbuffer) throws SocketException {
            this.acklist = ackbuffer;
        }

        public void receive(DatagramPacket packet) throws IOException {
            try {
                GUDPPacket gudpPacket = receive_list.take();
                gudpPacket.decapsulate(packet);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
        }

        private void receive_process() throws IOException {
            byte[] buf = new byte[GUDPPacket.MAX_DATAGRAM_LEN];
            DatagramPacket udpPacket = new DatagramPacket(buf, buf.length);
            datagramSocket.receive(udpPacket);
            GUDPPacket gudppacket = GUDPPacket.unpack(udpPacket);
            short type = gudppacket.getType();
            switch (type) {
                case GUDPPacket.TYPE_BSN:
                    seq = gudppacket.getSeqno() + 1;
                    receivebuffer.add(gudppacket);
                    send_ack(gudppacket);
                    break;
                case GUDPPacket.TYPE_DATA:
                    if(gudppacket.getSeqno()== seq) {
                        receivebuffer.add(gudppacket);
                        send_ack(gudppacket);
                        break;
                    }
                case GUDPPacket.TYPE_ACK:
                    acklist.add(gudppacket.getSeqno());
                    break;
            }
        }

        private void send_ack(GUDPPacket Packet) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(GUDPPacket.HEADER_SIZE);
            buffer.order(ByteOrder.BIG_ENDIAN);
            GUDPPacket ackPacket = new GUDPPacket(buffer);
            ackPacket.setType(GUDPPacket.TYPE_ACK);
            ackPacket.setSeqno(Packet.getSeqno() + 1);
            ackPacket.setVersion(GUDPPacket.GUDP_VERSION);
            ackPacket.setPayloadLength(0);
            ackPacket.setSocketAddress(Packet.getSocketAddress());
            datagramSocket.send(ackPacket.pack());
        }
        private void save_receivelist() throws IOException {
            while (true) {
                GUDPPacket packet = receivebuffer.peek();
                if (packet == null) {
                    break;
                }
                int seqNo = packet.getSeqno();
                if (seqNo < seq) {
                    receivebuffer.remove();
                    continue;
                }
                receivebuffer.remove();
                receive_list.add(packet);
                seq++;
                }
            }

        public void run() {
            while (this.IsRun) {
                try {
                    receive_process();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                try {
                    save_receivelist();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }
}

