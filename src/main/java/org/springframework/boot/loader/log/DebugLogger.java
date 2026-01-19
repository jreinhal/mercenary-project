/*
 * Decompiled with CFR 0.152.
 */
package org.springframework.boot.loader.log;

/*
 * Uses 'sealed' constructs - enablewith --sealed true
 */
public abstract class DebugLogger {
    private static final String ENABLED_PROPERTY = "loader.debug";
    private static final DebugLogger disabled = Boolean.getBoolean("loader.debug") ? null : new DisabledDebugLogger();

    public abstract void log(String var1);

    public abstract void log(String var1, Object var2);

    public abstract void log(String var1, Object var2, Object var3);

    public abstract void log(String var1, Object var2, Object var3, Object var4);

    public abstract void log(String var1, Object var2, Object var3, Object var4, Object var5);

    public static DebugLogger get(Class<?> sourceClass) {
        return disabled != null ? disabled : new SystemErrDebugLogger(sourceClass);
    }

    private static final class SystemErrDebugLogger
    extends DebugLogger {
        private final String prefix;

        SystemErrDebugLogger(Class<?> sourceClass) {
            this.prefix = "LOADER: " + sourceClass + " : ";
        }

        @Override
        public void log(String message) {
            this.print(message);
        }

        @Override
        public void log(String message, Object arg1) {
            this.print(message.formatted(arg1));
        }

        @Override
        public void log(String message, Object arg1, Object arg2) {
            this.print(message.formatted(arg1, arg2));
        }

        @Override
        public void log(String message, Object arg1, Object arg2, Object arg3) {
            this.print(message.formatted(arg1, arg2, arg3));
        }

        @Override
        public void log(String message, Object arg1, Object arg2, Object arg3, Object arg4) {
            this.print(message.formatted(arg1, arg2, arg3, arg4));
        }

        private void print(String message) {
            System.err.println(this.prefix + message);
        }
    }

    private static final class DisabledDebugLogger
    extends DebugLogger {
        private DisabledDebugLogger() {
        }

        @Override
        public void log(String message) {
        }

        @Override
        public void log(String message, Object arg1) {
        }

        @Override
        public void log(String message, Object arg1, Object arg2) {
        }

        @Override
        public void log(String message, Object arg1, Object arg2, Object arg3) {
        }

        @Override
        public void log(String message, Object arg1, Object arg2, Object arg3, Object arg4) {
        }
    }
}

