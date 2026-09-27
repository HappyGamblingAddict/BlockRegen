package nl.aurorion.blockregen.scheduler;

public interface TaskHandle {

    void cancel();

    String getId();
}
