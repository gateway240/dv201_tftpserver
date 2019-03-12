import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

public class TFTPServer {
	public static final int TFTPPORT = 4970; // instead of 69
	public static final int BUFSIZE = 516;
	public static final int TIMEOUT = 10000;
	public static final int TIMEOUT_WRITE = 50000;
	public static final String READDIR = "read/"; // custom address at my PC
	public static final String WRITEDIR = "write/"; // custom address at my PC
	
	// OP codes
	public static final int OP_RRQ = 1;
	public static final int OP_WRQ = 2;
	public static final int OP_DAT = 3;
	public static final int OP_ACK = 4;
	public static final int OP_ERR = 5;

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

		// Create socket for receiving the initial requests
		DatagramSocket socket = new DatagramSocket(null);

		// Create local bind point
		SocketAddress localBindPoint = new InetSocketAddress(TFTPPORT);
		socket.bind(localBindPoint);

		System.out.printf("Listening at port %d for new requests\n", TFTPPORT);

		// Loop to handle client requests
		while (true) {
			// receive the packet
			byte[] buf = new byte[BUFSIZE];
			DatagramPacket receivePacket = new DatagramPacket(buf, buf.length);
			try {
				socket.receive(receivePacket);
			} catch (IOException e) {
				continue;
			}


			// client Address and Port
			final InetAddress clientAddress = receivePacket.getAddress();
			final int tid = receivePacket.getPort();

			// If clientAddress is null, an error occurred in receiveFrom()
			if (clientAddress == null)
				continue;

			// the socket the handle the data connection randodm tid <--> random tid
			DatagramSocket sendSocket = new DatagramSocket(0); // 0 means any free port


			// extract the request type; should be write or read
			final int reqtype = (int) receivePacket.getData()[1]; // the opcode is in the first two bytes but max 5 so its always just the second byte

			// check if valid OP-Code
			if (reqtype != OP_RRQ && reqtype != OP_WRQ){
				checkError(receivePacket);
				send_ERR(sendSocket, 4, "Not Read request, nor Write Request", clientAddress, tid);
				continue;
			}


			// extract the filename
			final StringBuffer requestedFile = new StringBuffer();
			int i = 2; //filename starts at byte 2
			while (true) {
				if (receivePacket.getData()[i] == 0) {
					break;
				} else {
					requestedFile.append((char) receivePacket.getData()[i]);
				}
				i++;
			}

			// extract the data transfer mode
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

			// check if mode is octet
			if (!mode.toString().toLowerCase().equals("octet")){
				// only octet is supported for this assignment
				send_ERR(sendSocket, 0, "Only the octet mode is suported", clientAddress, tid);
				continue;
			}

			// asyn handle the actual data transfer
			new Thread() {
				public void run() {
					try {
						System.out.printf("%s request for %s from %s using port/tid %d\n",
								(reqtype == OP_RRQ) ? "Read" : "Write", requestedFile.toString(), clientAddress.getHostName(), tid);

						// Read request
						if (reqtype == OP_RRQ) {
							requestedFile.insert(0, READDIR);
							send_DATA_receive_ACK(sendSocket, requestedFile.toString(), tid, clientAddress);
						}
						// Write request
						else {
							requestedFile.insert(0, WRITEDIR);
							receive_DATA_send_ACK(sendSocket, requestedFile.toString(), tid, clientAddress);
						}						
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						try {
							sendSocket.close();
						} catch (Exception ignore) {
							ignore.printStackTrace();
						}
					}
				}
			}.start();
		}
	}


	// sends a data packet, waits for the acknowledgement, sends again, ... 
	private void send_DATA_receive_ACK(DatagramSocket sendSocket, String requestedFile, int tid, InetAddress inetAddress) {
		System.out.println("start sending file: " + requestedFile);
		int resend = 0; // 0 means do not resend, all positive numbers mean x false attempts
		int sendPacketIndex = 1;


		// check if file exists
		File fileToSend = new File(requestedFile);
		if (!fileToSend.exists()) {
			send_ERR(sendSocket, 1, "Error - File not Found", inetAddress, tid);
			return;
		}

		FileInputStream filestream = null;
		try {
			// filestream
			try {
				filestream = new FileInputStream(fileToSend);
			} catch (Exception e) {
				send_ERR(sendSocket, 2, "Error - File can not be accessed", inetAddress, tid);
				return;
			}
			
			byte[] buffer = new byte[BUFSIZE];
			int len = -4; // -4 is never used
			while (true) {
				if (resend==0){
					// if no resend
					//# buffer = new byte[BUFSIZE];

					// fill the buffer
					try {
						len = filestream.read(buffer, 4, 512);
					} catch (Exception e) {
						send_ERR(sendSocket, 2, "Error - File can not be readen", inetAddress, tid);
						return;
					}					
					buffer[1] = OP_DAT;
					buffer[2] = (byte) ((sendPacketIndex & 0x0000FF00) >> 8);
					buffer[3] = (byte) (sendPacketIndex & 0x000000FF);
				}else if (resend > 5){
					// if we already tried 5 times to send a specific data block
					send_ERR(sendSocket, 0, "Error - sending failed 5 times on the same packet", inetAddress, tid);
					return;
				}
				
				// send the data packet
				DatagramPacket sendPacket = new DatagramPacket(buffer, len+4, inetAddress, tid);
				sendSocket.send(sendPacket);
				System.out.println("Send packet " + sendPacketIndex);


				// receive the acknowledgement
				byte[] AckBuf = new byte[BUFSIZE];
				DatagramPacket receivePacket = new DatagramPacket(AckBuf, AckBuf.length);
				sendSocket.setSoTimeout(TIMEOUT); // if you get a unknown TID, than the timeout starts from beginning. This happens so unlikely that I do not care
				do {
					try {
						//System.out.println("waiting for ack");
						sendSocket.receive(receivePacket);
						if (receivePacket.getPort() != tid){
							send_ERR(sendSocket, 5, "Error - wrong transfer ID. Connection is not closed", receivePacket.getAddress(), receivePacket.getPort());
						}
					} catch (SocketTimeoutException e) {					
						System.out.println("Ack Timeout");
						resend++;
						continue; // sends the same message again
					} catch (Exception e) {
						e.printStackTrace();
						send_ERR(sendSocket, 0, "unknown Error - receiving the Ack message", inetAddress, tid);
						return;
					}
				} while (receivePacket.getPort() != tid);


				// extracting Opcode and acknowledget block index
				int ackOpcode = receivePacket.getData()[1];
				int ackIndex = Byte.toUnsignedInt(receivePacket.getData()[2]) * 256	+ Byte.toUnsignedInt(receivePacket.getData()[3]);
				
				
				// check the Op-Code
				if (ackOpcode != OP_ACK){
					checkError(receivePacket);
					send_ERR(sendSocket, 4, "Error - Opcode was not for ACK", inetAddress, tid);
					return;
				}
				

				// check the acknowledgment block index
				if (ackIndex != sendPacketIndex) {
					System.err.println("received index was wrong: " + ackIndex);
					resend++; // not neccessary but assignment says...
					continue; // sends the same message again
				}

				System.out.println("received Ack for " + ackIndex);

				// everything worked fine and we can send the next data packet
				sendPacketIndex++;
				resend = 0;

				if (sendPacketIndex >= Math.pow(2, 16) - 1){
					// maximum count of data blocks reached.
					// In this implementation of the basis TFTP from the RFC 1350, we can not handle this case
					send_ERR(sendSocket, 0, "Error - Maximum data size of almost 32 MiB reached ", inetAddress, tid);
					return;
				}

				// if file was completly written, stop the data transfer
				if (len != 512)
					break;
			}
			// file is completly transfered
		} catch (Exception e) {
			e.printStackTrace();
			send_ERR(sendSocket, 0, "unknown Error", inetAddress, tid);
			return;
		} finally {
			if (filestream != null){
				try {
					filestream.close();
				} catch (Exception ignore) {}				
			}			
		}
	}


	// sends the first ack. so the client starts the data transfer. Then gets the data, sends the ack, gets the data, ...
	private void receive_DATA_send_ACK(DatagramSocket sendSocket, String requestedFile, int tid, InetAddress inetAddress) {
		System.out.println("start receiving file: " + requestedFile);

		// check if file already exists
		File fileToSave = new File(requestedFile);
		if (fileToSave.exists()){
			send_ERR(sendSocket, 6, "Error - File already exists", inetAddress, tid);
			return;
		}

		// for checking if the received data packet is the right one in the order
		int blockIndexReceived = 0;

		FileOutputStream filestream = null;
		try {
			// output stream
			try {
				filestream = new FileOutputStream(fileToSave);
			} catch (IOException e) {
				send_ERR(sendSocket, 2, "Error - File cannot be opened", inetAddress, tid); // 2 = Access Violation
				return;
			}
			

			// send first Ack to the client, so it starts sending the data
			byte[] startAckBuf = new byte[4]; // Ack messages are always 4 Bytes
			startAckBuf[1] = OP_ACK;
			startAckBuf[2] = 0;
			startAckBuf[3] = 0; // first Ack for a write request has "index" 0
			DatagramPacket sendStartPacket = new DatagramPacket(startAckBuf, 4, inetAddress, tid);
			sendSocket.send(sendStartPacket);
			System.out.println("Send starting Ack");

			// getting the data and sending the ack until the data packet is smaller than 512 bytes respectivly UDP packet smaller than 516
			byte[] buffer = new byte[BUFSIZE];
			while (true) {
				// receiving the data
				DatagramPacket receivePacket = new DatagramPacket(buffer, buffer.length);
				sendSocket.setSoTimeout(TIMEOUT_WRITE); // if you get a unknown TID, than the timeout starts from beginning. This happens so rarely that it is not relevant
				do {
					try {
						sendSocket.receive(receivePacket);
						if (receivePacket.getPort() != tid) {
							send_ERR(sendSocket, 5, "Error - wrong transfer ID. Connection is not closed", receivePacket.getAddress(), receivePacket.getPort());
						}
					} catch (SocketTimeoutException e) {
						System.out.println("Data Timeout");
						send_ERR(sendSocket, 0, "Error - waiting for the data timeout", inetAddress, tid);
						return;
					} catch (Exception e) {
						e.printStackTrace();
						send_ERR(sendSocket, 0, "unknown Error - receiving the data", inetAddress, tid);
						return;
					}
				} while (receivePacket.getPort() != tid);


				// extracting and checking the Op-Code
				int opcode = receivePacket.getData()[1];
				if (opcode != OP_DAT){
					checkError(receivePacket);
					send_ERR(sendSocket, 4, "Error - Opcode was not for DATA", inetAddress, tid);
					return;
				}

				// extracting and checking the data block index
				int blockIndex = Byte.toUnsignedInt(receivePacket.getData()[2]) * 256 + Byte.toUnsignedInt(receivePacket.getData()[3]);
				if (blockIndex != blockIndexReceived+1){
					// ignore a data packet that has not the right index
					System.err.println("received a data packet with a wrong block index: " + blockIndex);


					// send Ack
					byte[] ackBuf = new byte[4]; // Ack messages are always 4 Bytes
					ackBuf[1] = OP_ACK;
					ackBuf[2] = (byte) ((blockIndex & 0x0000FF00) >> 8);
					ackBuf[3] = (byte) (blockIndex & 0x000000FF);
					DatagramPacket sendPacket = new DatagramPacket(ackBuf, 4, inetAddress, tid);
					sendSocket.send(sendPacket);
					System.out.println("Send Ack " + blockIndex);
					continue;
				}
				blockIndexReceived = blockIndex;
				System.out.println("received packet: " + blockIndex);
				if (blockIndex >= Math.pow(2, 16)-1) {
					// maximum count of data blocks reached.
					// In this implementation of the basis TFTP from the RFC 1350, we can not handle this case
					System.err.println("maximum count of data blocks reached. - Now we maybe gonna see many errors...");

				}

				// writes the data in the file
				try {
					filestream.write(receivePacket.getData(), 4, receivePacket.getLength()-4);
				} catch (IOException e) {
					send_ERR(sendSocket, 3, "Error - writing the data into the file", inetAddress, tid);
					return;
				}				

				// send Ack
				byte[] ackBuf = new byte[4]; // Ack messages are always 4 Bytes
				ackBuf[1] = OP_ACK;
				ackBuf[2] = (byte) ((blockIndex & 0x0000FF00) >> 8);
				ackBuf[3] = (byte) (blockIndex & 0x000000FF);
				DatagramPacket sendPacket = new DatagramPacket(ackBuf, 4, inetAddress, tid);
				sendSocket.send(sendPacket);
				System.out.println("Send Ack " + blockIndex);

				// if this was the last packet, stop the transfer
				if (receivePacket.getLength() != 516)
					break;
			}
			// the whole file is received
		} catch (Exception e) {
			e.printStackTrace();
			send_ERR(sendSocket, 0, "unknown Error", inetAddress, tid);
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


	// sends and prints a error message
	private void send_ERR(DatagramSocket sendSocket, int errorcode, String message, InetAddress inetAddress, int port) {
		System.err.println("Errormessage: " + message + " with code: " + errorcode);

		// creates the message
		byte[] buffer = new byte[5+message.getBytes().length];
		buffer[1] = OP_ERR;
		buffer[2] = 0;
		buffer[3] = (byte) errorcode;
		System.arraycopy(message.getBytes(), 0, buffer, 4, message.getBytes().length);
		DatagramPacket errorPacket = new DatagramPacket(buffer, buffer.length, inetAddress, port);

		// sends the message
		try {
			sendSocket.send(errorPacket);
			sendSocket.close();
		} catch (Exception ignore) {
			System.out.println("error message could not be send");
		}
		
	}


	// checks for a received packet. if it is an error message then print the message
	private void checkError(DatagramPacket receivePacket){
		// extract the Op-Code
		int opcode = receivePacket.getData()[1];

		if (opcode == OP_ERR){
			// Op-Code is for Errors

			// extracts the error-code
			int errorcode = receivePacket.getData()[3];			
		
			// extracts the error message
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

			// print error
			System.err.println("Received Error message: '" + errormessage.toString() + "' with code: " + errorcode);
		}
	}
}