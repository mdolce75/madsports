package com.madsports.monitor;

import com.madsports.sidgad.model.Federation;

import java.util.ArrayList;
import java.util.List;

/** Federaciones que se precargan y se chequean cada día: las configuradas, más la AMP cuando está habilitada. */
final class Monitored {

    private Monitored() {
    }

    static List<Federation> federations(List<Federation> configured, boolean amp) {
        List<Federation> out = new ArrayList<>(configured);
        if (amp && !out.contains(Federation.AMP)) {
            out.add(Federation.AMP);
        }
        return List.copyOf(out);
    }
}
