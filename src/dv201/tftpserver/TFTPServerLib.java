package dv201.tftpserver;

public class TFTPServerLib {
    public static final int TFTPPORT = 4970; // instead of 69
    public static final int BUFSIZE = 516;
    public static final int TIMEOUT = 10000;
    public static final int TIMEOUT_WRITE = 50000;
    public static final String READDIR = "read/"; // custom address at your PC
    public static final String WRITEDIR = "write/"; // custom address at your PC
    // OP codes
//    public static final int OP_RRQ = 1;
//    public static final int OP_WRQ = 2;
//    public static final int OP_DAT = 3;
//    public static final int OP_ACK = 4;
//    public static final int OP_ERR = 5;
    // TODO   3         Disk full or allocation exceeded.
    // TODO   4         Illegal TFTP operation. --> Started
    // TODO   5         Unknown transfer ID. --> I do not get this messages because the socket connect, so???

}
