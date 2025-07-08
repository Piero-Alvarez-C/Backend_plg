package pe.pucp.plg.model.control;

public class SimulationControlState {

    private volatile boolean paused = false;
    private volatile long stepDelayMs = 300; 

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public long getStepDelayMs() {
        return stepDelayMs;
    }

    public void setStepDelayMs(long stepDelayMs) {
        this.stepDelayMs = Math.max(0, stepDelayMs);
    }
}