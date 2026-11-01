package xwdy.deepseek.test;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Entire remote call is bounded, including start, stream acquisition, wait and cleanup. */
public final class ShellRunner {
    public interface Factory { Process start(String command) throws Exception; }
    public static final class Result {
        public final int code;
        public final boolean timedOut;
        public final String out, err;
        public Result(int code, boolean timeout, String out, String err) {
            this.code=code; this.timedOut=timeout; this.out=out; this.err=err;
        }
        public boolean ok() { return code==0 && !timedOut; }
        public String text() { return "exit="+code+" timeout="+timedOut+"\n"+out+"\n"+err; }
    }
    // A stuck Binder call must not create an unlimited number of worker threads.
    private static final Semaphore SLOTS=new Semaphore(2);
    private static final class Drain implements Runnable {
        final InputStream stream; final StringBuilder text=new StringBuilder();
        boolean truncated; volatile String error="";
        Drain(InputStream stream) { this.stream=stream; }
        public void run() {
            try (Reader reader=new InputStreamReader(stream,"UTF-8")) {
                char[] buffer=new char[2048]; int n;
                while((n=reader.read(buffer))!=-1) synchronized(this) {
                    int remaining=Math.max(0,65536-text.length());
                    text.append(buffer,0,Math.min(n,remaining));
                    if(n>remaining) truncated=true;
                }
            } catch(Exception e) { error=e.toString(); }
        }
        synchronized String value() { return text.toString()+(truncated?"\n[output truncated]":""); }
    }
    private static Thread daemon(String name,Runnable task) {
        Thread thread=new Thread(task,name);thread.setDaemon(true);thread.start();return thread;
    }
    private static void safeDestroy(Process process) {
        if(process==null) return;
        try { process.destroy(); } catch(RuntimeException disconnected) { }
    }
    private static void closeDrain(Drain drain) {
        if(drain==null) return;
        try { drain.stream.close(); } catch(Exception ignored) { }
    }
    private static String value(Drain drain) { return drain==null?"":drain.value(); }
    public static Result run(Factory factory,String command,long timeoutMs) {
        if(!SLOTS.tryAcquire()) return new Result(-1,true,"","Remote calls remain blocked; restart Shizuku before retrying");
        final AtomicBoolean abandoned=new AtomicBoolean();
        final AtomicReference<Process> process=new AtomicReference<>();
        final AtomicReference<Drain> stdout=new AtomicReference<>(),stderr=new AtomicReference<>();
        FutureTask<Result> task=new FutureTask<>(() -> {
            try {
                Process p=factory.start(command);process.set(p);
                if(abandoned.get()) return new Result(-1,true,"","Late remote launch; state unknown");
                try { p.getOutputStream().close(); } catch (RuntimeException ignored) { }
                Drain out=new Drain(p.getInputStream());stdout.set(out);
                Drain err=new Drain(p.getErrorStream());stderr.set(err);
                Thread a=daemon("shell-stdout",out),b=daemon("shell-stderr",err);
                // Some Shizuku/ColorOS combinations make remote waitFor() throw
                // "process hasn't exited" while the command is still running.
                // Poll exitValue() with a deadline instead.
                int code=-1;
                long deadline=System.currentTimeMillis()+Math.max(1000L,timeoutMs-1500L);
                boolean exited=false;
                while(!exited) {
                    try { code=p.exitValue(); exited=true; }
                    catch(RuntimeException e) {
                        String message=String.valueOf(e.getMessage());
                        if(!message.contains("hasn't exited")&&!message.contains("IllegalArgumentException")) throw e;
                        if(System.currentTimeMillis()>deadline) {
                            safeDestroy(p);
                            a.join(500);b.join(500);
                            return new Result(-1,true,out.value(),err.value()+"\nRemote operation still running after timeout");
                        }
                        try { Thread.sleep(80); }
                        catch(InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            safeDestroy(p);
                            a.join(500);b.join(500);
                            return new Result(-1,true,out.value(),err.value()+"\nInterrupted; remote state unknown");
                        }
                    }
                }
                a.join(1000);b.join(1000);
                boolean incomplete=a.isAlive()||b.isAlive()||!out.error.isEmpty()||!err.error.isEmpty();
                return new Result(code==0&&incomplete?-3:code,false,out.value(),err.value()
                    +(incomplete?"\nOutput incomplete: "+out.error+" "+err.error:""));
            } finally {
                try { safeDestroy(process.get());closeDrain(stdout.get());closeDrain(stderr.get()); }
                finally { SLOTS.release(); }
            }
        });
        daemon("shell-call",task);
        try { return task.get(timeoutMs,TimeUnit.MILLISECONDS); }
        catch(TimeoutException e) {
            abandoned.set(true);
            daemon("shell-cancel",() -> safeDestroy(process.get()));
            return new Result(-1,true,value(stdout.get()),value(stderr.get())+"\nRemote operation timed out; state unknown");
        } catch(InterruptedException e) {
            abandoned.set(true);Thread.currentThread().interrupt();
            daemon("shell-cancel",() -> safeDestroy(process.get()));
            return new Result(-1,true,value(stdout.get()),"Interrupted; remote state unknown");
        } catch(ExecutionException e) {
            Throwable cause=e.getCause();
            return new Result(-1,false,value(stdout.get()),value(stderr.get())+"\n"+cause);
        }
    }
}