package dv201.tftpserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import static dv201.tftpserver.TFTPServerLib.BUFSIZE;
import static dv201.tftpserver.TFTPServerLib.OP_ACK;
import static dv201.tftpserver.TFTPServerLib.OP_DAT;
import static dv201.tftpserver.TFTPServerLib.OP_ERR;
import static dv201.tftpserver.TFTPServerLib.OP_RRQ;
import static dv201.tftpserver.TFTPServerLib.OP_WRQ;
import static dv201.tftpserver.TFTPServerLib.READDIR;
import static dv201.tftpserver.TFTPServerLib.TFTPPORT;
import static dv201.tftpserver.TFTPServerLib.TIMEOUT;
import static dv201.tftpserver.TFTPServerLib.TIMEOUT_WRITE;
import static dv201.tftpserver.TFTPServerLib.WRITEDIR;

public class TFTPServer {

	public static void main(String[] args) {
		if (args.length > 0) {
			System.err.printf("usage: java %s\n", TFTPServer.class.getCanonicalName());
			System.exit(1);
		}
		// Starting the server
		try {
			TFTPServer server = new TFTPServer();
			server.start();
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}

	private void start() throws SocketException {

		// Create socket
		DatagramSocket socket = new DatagramSocket(null);

		// Create local bind point
		SocketAddress localBindPoint = new InetSocketAddress(TFTPPORT);
		socket.bind(localBindPoint);

		System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

		// Loop to handle client requests
		while (true) {
			byte[] buf = new byte[BUFSIZE];

			// Create datagram packet
			DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);

			// Receive packet

			try {
				socket.receive(receivePacket);
			} catch (IOException e) {
				continue;
			}

			// new String(receivePacket.getData(), receivePacket.getOffset(),
			// receivePacket.getLength());

			final InetSocketAddress clientAddress = new InetSocketAddress(receivePacket.getAddress(), receivePacket.getPort());

			// If clientAddress is null, an error occurred in receiveFrom()
			if (clientAddress == null)
				continue;

			DatagramSocket sendSocket = new DatagramSocket(0); // 0 means any free port

			final StringBuffer requestedFile = new StringBuffer();
			final int reqtype = (int) receivePacket.getData()[1]; // the opcode is in the first two bytes but max 5 so its always just the second byte

			if (reqtype != OP_RRQ && reqtype != OP_WRQ){
				checkError(receivePacket);
				send_ERR(sendSocket, 4, "Not Read request, nor Write Request");
				continue;
			}

			// file name
			int i = 2;
			while (true) {
				if (receivePacket.getData()[i] == 0) {
					break;
				} else {
					requestedFile.append((char) receivePacket.getData()[i]);
				}
				i++;
			}

			// mode
			i++;
			final StringBuffer mode = new StringBuffer();
			while (true) {
				if (receivePacket.getData()[i] == 0) {
					break;
				} else {
					mode.append((char) receivePacket.getData()[i]);
				}
				i++;
			}
			System.out.println("mode: " + mode.toString());

			new Thread() {
				public void run() {
					try {
						// Connect to client
						sendSocket.connect(clientAddress); // no foreign messages

						System.out.printf("%s request for %s from %s using port %d\n",
								(reqtype == OP_RRQ) ? "Read" : "Write", requestedFile.toString(), clientAddress.getHostString(),
								clientAddress.getPort());

						// Read request
						if (reqtype == OP_RRQ) {
							requestedFile.insert(0, READDIR);
							send_DATA_receive_ACK(sendSocket, requestedFile.toString());
						}
						// Write request
						else {
							requestedFile.insert(0, WRITEDIR);
							receive_DATA_send_ACK(sendSocket, requestedFile.toString());
						}
						sendSocket.close();
					} catch (SocketException e) {
						e.printStackTrace();
					}
				}
			}.start();
		}
	}


	private void send_DATA_receive_ACK(DatagramSocket sendSocket, String requestedFile) {
		System.out.println("start sending file: " + requestedFile);
		int resend = 0; // 0 means do not resend, all positive numbers mean x false attempts
		int sendPacketIndex = 1;

		File fileToSend = new File(requestedFile);
		if (!fileToSend.exists()) {
			send_ERR(sendSocket, 1, "Error - File not Found");
			return;
		}

		FileInputStream filestream = null;
		try {
			try {
				filestream = new FileInputStream(fileToSend);
			} catch (Exception e) {
				send_ERR(sendSocket, 2, "Error - File can not be accessed");
				return;
			}
			
			byte[] buffer = new byte[BUFSIZE];
			int len = -4; // -4 is never used
			while (true) {
				if (resend==0){
					buffer = new byte[BUFSIZE];
					try {
						len = filestream.read(buffer, 4, 512);
					} catch (Exception e) {
						send_ERR(sendSocket, 2, "Error - File can not be readen");
						return;
					}					
					buffer[1] = OP_DAT;
					buffer[2] = (byte) ((sendPacketIndex & 0x0000FF00) >> 8);
					buffer[3] = (byte) (sendPacketIndex & 0x000000FF);
				}else if (resend > 5){
					send_ERR(sendSocket, 0, "Error - sending failed 5 times on the same packet");
					return;
				}
				
				DatagramPacket sendPacket = new DatagramPacket(buffer, len+4, sendSocket.getInetAddress(), sendSocket.getPort());

				sendSocket.send(sendPacket);

				System.out.println("Send packet " + sendPacketIndex);

				int ackOpcode = -1;
				int ackIndex = -1;

				byte[] AckBuf = new byte[BUFSIZE];
				DatagramPacket receivePacket = new DatagramPacket(AckBuf, AckBuf.length);
				sendSocket.setSoTimeout(TIMEOUT);
				try {
					//System.out.println("waiting for ack");
					sendSocket.receive(receivePacket);
				} catch (SocketTimeoutException e) {					
					System.out.println("Ack Timeout");
					resend++;
					continue; // sends the same message again
				} catch (Exception e) {
					e.printStackTrace();
					send_ERR(sendSocket, 0, "unknown Error - receiving the Ack message");
					return;
				}

				ackOpcode = receivePacket.getData()[1];
				ackIndex = Byte.toUnsignedInt(receivePacket.getData()[2]) * 256
						+ Byte.toUnsignedInt(receivePacket.getData()[3]);
						
				if (ackOpcode != OP_ACK){
					checkError(receivePacket);
					send_ERR(sendSocket, 4, "Error - Opcode was not for ACK");
					return;
				}
				
				if (ackOpcode != OP_ACK || ackIndex != sendPacketIndex) {
					System.err.println("received index was wrong: " + ackIndex);
					resend++;
					continue; // sends the same message again
				}

				System.out.println("received Ack for " + ackIndex);

				sendPacketIndex++;
				resend = 0;

				if (len != 512)
					break;
			}
			filestream.close();
		} catch (Exception e) {
			e.printStackTrace();
			send_ERR(sendSocket, 0, "unknown Error");
			return;
		} finally {
			if (filestream != null){
				try {
					filestream.close();
				} catch (Exception ignore) {}				
			}			
		}		
		return;
	}

	private void receive_DATA_send_ACK(DatagramSocket sendSocket, String requestedFile) {
		System.out.println("start receiving file: " + requestedFile);

		File fileToSave = new File(requestedFile);
		if (fileToSave.exists()){
			send_ERR(sendSocket, 6, "Error - File already exists");
			return;
		}

		FileOutputStream filestream = null;
		try {
			filestream = new FileOutputStream(fileToSave);

			// send first Ack for starting the writing
			byte[] startAckBuf = new byte[4]; // Ack messages are always 4 Bytes
			startAckBuf[1] = OP_ACK;
			startAckBuf[2] = 0;
			startAckBuf[3] = 0;

			DatagramPacket sendStartPacket = new DatagramPacket(startAckBuf, 4, sendSocket.getInetAddress(),
					sendSocket.getPort());

			sendSocket.send(sendStartPacket);

			System.out.println("Send starting Ack");
			
			while (true) {

				byte[] buffer = new byte[BUFSIZE];
				DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
				sendSocket.setSoTimeout(TIMEOUT_WRITE);

				try {
					// System.out.println("waiting for data");
					sendSocket.receive(receivePacket);
				} catch (SocketTimeoutException e) {
					System.out.println("Data Timeout");
					send_ERR(sendSocket, 0, "Error - waiting for the data timeout");
					return;
				} catch (Exception e) {
					e.printStackTrace();
					send_ERR(sendSocket, 0, "unknown Error - receiving the data");
					return;
				}


				int opcode = receivePacket.getData()[1];
				if (opcode != OP_DAT){
					checkError(receivePacket);
					send_ERR(sendSocket, 4, "Error - Opcode was not for DATA");
					return;
				}
				int blockIndex = Byte.toUnsignedInt(receivePacket.getData()[2]) * 256
						+ Byte.toUnsignedInt(receivePacket.getData()[3]);
				
				System.out.println("received packet: " + blockIndex);

				filestream.write(receivePacket.getData(), 4, receivePacket.getLength()-4);

				// send Ack
				byte[] ackBuf = new byte[4]; // Ack messages are always 4 Bytes
				ackBuf[1] = OP_ACK;
				ackBuf[2] = (byte) ((blockIndex & 0x0000FF00) >> 8);
				ackBuf[3] = (byte) (blockIndex & 0x000000FF);
				

				DatagramPacket sendPacket = new DatagramPacket(ackBuf, 4, sendSocket.getInetAddress(),
						sendSocket.getPort());

				sendSocket.send(sendPacket);

				System.out.println("Send Ack " + blockIndex);

				if (receivePacket.getLength() != 516)
					break;
			}
			filestream.close();
		} catch (Exception e) {
			e.printStackTrace();
			send_ERR(sendSocket, 0, "unknown Error");
			return;
		} finally {
			if (filestream != null) {
				try {
					filestream.close();
				} catch (Exception ignore) {
				}
			}
		}
		return;
	}

	private void send_ERR(DatagramSocket sendSocket, int errorcode, String message) {
		System.err.println(message);
		byte[] buffer = new byte[5+message.getBytes().length];
		buffer[1] = OP_ERR;
		buffer[2] = 0;
		buffer[3] = (byte) errorcode;
		System.arraycopy(message.getBytes(), 0, buffer, 0, message.getBytes().length);
		DatagramPacket errorPacket = new DatagramPacket(buffer, buffer.length, sendSocket.getInetAddress(),
				sendSocket.getPort());

		try {
			sendSocket.send(errorPacket);
			sendSocket.close();
		} catch (Exception ignore) {
			//ignore because it is error
		}
		
	}

	private void checkError(DatagramPacket receivePacket){
		int opcode = receivePacket.getData()[1];

		if (opcode == OP_ERR){
			int errorcode = receivePacket.getData()[3];			
		

			final StringBuffer errormessage = new StringBuffer();
			int i = 4;
			while (true) {
				if (receivePacket.getData()[i] == 0) {
					break;
				} else {
					errormessage.append((char) receivePacket.getData()[i]);
				}
				i++;
			}
			System.err.println("Received Error message:\nmessage: " + errormessage.toString() + "; code: " + errorcode);
		}
	}

}
