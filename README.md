# Building-Reliable-UDP-Protocol
Project code for KTH course IK2215 Advanced Internetworking 
In this section, the primary functionality revolves around GUDPSocket.java.

To test the project, you'll need to initiate both the sender and the receiver, simulating the transmission of data between two computers. You must provide a .txt file containing the message that the sender will transmit to the receiver.

First, start the receiver by running the command: java VSRecv -d <Port number>

Next, launch the sender using the command: javac VSSend.java; java VSSend -d 127.0.0.1:<Port number> <message.txt>
