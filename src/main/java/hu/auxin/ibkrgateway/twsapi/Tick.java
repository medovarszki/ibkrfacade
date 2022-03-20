package hu.auxin.ibkrgateway.twsapi;

public class Tick {
    public double bid;
    public double ask;
    public int bidSize;
    public int askSize;
    public double last;
    public long modified_at;
    public int lastTradeVolume;

    public boolean shortable;
}