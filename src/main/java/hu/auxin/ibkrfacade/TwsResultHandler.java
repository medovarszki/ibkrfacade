package hu.auxin.ibkrfacade;

import java.util.Map;
import java.util.WeakHashMap;

public final class TwsResultHandler {

    private final static Map<Integer, TwsResultHolder> results = new WeakHashMap<>();

    public void setResult(int requestId, TwsResultHolder result) {
        results.put(requestId, result);
    }

    public TwsResultHolder getResult(int requestId) {
        //TODO not the most sophisticated wait mechanism
        while(!results.containsKey(requestId)) {
            continue;
        }
        return results.get(requestId);
    }

}
