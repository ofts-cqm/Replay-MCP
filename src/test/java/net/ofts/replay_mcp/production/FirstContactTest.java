package net.ofts.replay_mcp.production;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirstContactTest {
    @Test void earliestObstacleWinsRegardlessOfScanOrderAndRangeLength() {
        double[] start={235.5,164.6,220.5}, end={235.5,164.6,225.5};
        double[] nearMin={235,164,223.4375}, nearMax={236,165,223.5625};
        double[] farMin={234,164,224.4375}, farMax={235,165,224.5625};
        double near=SweptClearance.firstIntersection(start,end,nearMin,nearMax,.5);
        double far=SweptClearance.firstIntersection(start,end,farMin,farMax,.5);
        var hits=new SweptClearance.FirstContact<String>();
        hits.consider(far,"far"); hits.consider(near,"near"); hits.consider(Double.NaN,"none");
        assertEquals("near",hits.obstacle());
        assertEquals(1462500,3000000*hits.parameter(),1e-6);
        double prefix=SweptClearance.firstIntersection(start,new double[]{235.5,164.6,223.5},nearMin,nearMax,.5);
        assertEquals(3000000*hits.parameter(),1800000*prefix,1e-6);
        hits.consider(near,"equal later obstacle");
        assertEquals("near",hits.obstacle());
    }
    @Test void emptyAndInvalidContactsCannotBlock() {
        var hits=new SweptClearance.FirstContact<String>();
        hits.consider(Double.NaN,"none"); hits.consider(-1,"outside"); hits.consider(2,"outside");
        assertFalse(hits.found());
        hits.consider(0,"start"); assertTrue(hits.found()); assertEquals(0,hits.parameter());
    }
}
