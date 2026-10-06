package com.bank.rcp.agents;

import java.util.*;
import java.util.regex.Pattern;

/** Behavioural guardrails shared by all agents (OWASP ASI01/ASI02/ASI09 controls). */
public final class Guards {
    private Guards() {}

    /** Requests for actions reserved to deterministic services and named humans (hard rule, Section 7.2). */
    private static final List<Pattern> PROHIBITED = List.of(
            Pattern.compile("(?i)\\b(please\\s+)?(approve|authori[sz]e|green-?light|sign off)\\b.{0,50}\\b(transfer|mechanism|case|policy|notice|vendor|disclosure|pr)\\b"),
            Pattern.compile("(?i)\\b(send|submit|file|transmit)\\b.{0,40}\\b(notice|notification|breach report|incident report|to the regulator|to mas|to cert-in|to the dpc)\\b"),
            Pattern.compile("(?i)\\b(deploy|merge|promote|publish)\\b.{0,40}\\b(polic(y|ies)|rule|bundle)\\b"),
            Pattern.compile("(?i)\\brecord\\b.{0,40}\\b(mechanism|scc|standard contract|filing)\\b"),
            Pattern.compile("(?i)\\b(change|alter|modify|edit|adjust|round)\\b.{0,40}\\b(report figure|figures|the number|report value)\\b"),
            Pattern.compile("(?i)\\b(stop|pause|extend|reset)\\b.{0,30}\\bclocks?\\b"),
            Pattern.compile("(?i)\\b(allow|unblock|force)\\b.{0,30}\\btransfer\\b"));

    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile("(?i)ignore (all |any )?(previous|prior|above) instructions"),
            Pattern.compile("(?i)system override"),
            Pattern.compile("(?i)you are now (in )?\\w+"),
            Pattern.compile("(?i)admin mode"),
            Pattern.compile("(?i)note to ai"),
            Pattern.compile("(?i)\\bcall\\s+[a-z]+\\.[a-z_]+"),
            Pattern.compile("(?i)approve (all|every)"),
            Pattern.compile("(?i)disable (all )?.{0,20}rules"));

    public static Optional<String> prohibitedAction(String text) {
        if (text == null) return Optional.empty();
        for (Pattern p : PROHIBITED) {
            var m = p.matcher(text);
            if (m.find()) return Optional.of(m.group());
        }
        return Optional.empty();
    }

    public static List<String> injectionSignals(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (Pattern p : INJECTION) {
            var m = p.matcher(text);
            if (m.find()) out.add(m.group());
        }
        return out;
    }

    public static String refusal(String matched) {
        return "Refused: '" + matched + "' is an action with regulatory effect. Under principle P1 it belongs to a deterministic service and a named human approver. "
                + "I can prepare the analysis, a draft case, or a simulation for the approver instead.";
    }
}
