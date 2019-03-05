package dv201.tftpserver.enums;

public enum OpCodes {
    OP_RRQ (1),
    OP_WRQ (2),
    OP_DAT (3),
    OP_ACK (4),
    OP_ERR (5);

    private final int opCode;
    OpCodes(int opCode) { this.opCode = opCode; }
    public int getValue() { return opCode; }
}
