package com.jreinhal.mercenary.security;

import java.util.List;
import java.util.regex.Pattern;

public final class PromptInjectionPatterns {
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("ignore\\s+(all\\s+)?(previous|prior|above)\\s+(instructions?|prompts?|rules?)", 2),
            Pattern.compile("disregard\\s+(all\\s+)?(previous|prior|above)", 2),
            Pattern.compile("forget\\s+(all\\s+)?(previous|prior|your)\\s+(instructions?|context|rules?)", 2),
            Pattern.compile("(show|reveal|display|print|output)\\s+(me\\s+)?(the\\s+)?(system|initial)\\s+prompt", 2),
            Pattern.compile("what\\s+(is|are)\\s+your\\s+(system\\s+)?(instructions?|rules?|prompt)", 2),
            Pattern.compile("you\\s+are\\s+now\\s+(a|an|in)\\s+", 2),
            Pattern.compile("act\\s+as\\s+(if|though)\\s+you", 2),
            Pattern.compile("pretend\\s+(to\\s+be|you\\s+are)", 2),
            Pattern.compile("roleplay\\s+as", 2),
            Pattern.compile("\\bDAN\\b.*mode", 2),
            Pattern.compile("developer\\s+mode\\s+(enabled|on|activated)", 2),
            Pattern.compile("bypass\\s+(your\\s+)?(safety|security|restrictions?|filters?)", 2),
            Pattern.compile("```\\s*(system|assistant)\\s*:", 2),
            Pattern.compile("\\[INST\\]|\\[/INST\\]|<<SYS>>|<</SYS>>", 2),
            Pattern.compile("(start|begin)\\s+(your\\s+)?response\\s+with", 2),
            Pattern.compile("(what|tell|show|reveal|repeat|print|display).{0,15}(your|system|internal|hidden).{0,10}(prompt|instructions?|directives?|rules|guidelines)", 2),
            Pattern.compile("(what|how).{0,10}(are|were).{0,10}you.{0,10}(programmed|instructed|told|prompted)", 2),
            Pattern.compile("(ignore|forget|disregard).{0,20}(previous|above|prior|all).{0,20}(instructions?|prompt|rules|context)", 2),
            Pattern.compile("(repeat|echo|output).{0,15}(everything|all).{0,10}(above|before|prior)", 2)
    );

    private PromptInjectionPatterns() {
    }

    public static List<Pattern> getPatterns() {
        return PATTERNS;
    }
}
