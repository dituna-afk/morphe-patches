private final Runnable progressTicker = new Runnable() {
        @Override
        public void run() {
            publishState();
            if (playerReady() && player.isPlaying()) {
                progressHandler.postDelayed(this, 500);
            }
        }
    };

    private void resume() {
        if (!playerReady()) return;
        if (audioFocusDenied()) return;
        player.start();
        progressHandler.removeCallbacks(progressTicker);
        progressHandler.post(progressTicker);
        publishState();
        notifyChanged();
    }

    private void pause() {
        if (!playerReady() || !player.isPlaying()) return;
        player.pause();
        progressHandler.removeCallbacks(progressTicker);
        publishState();
        notifyChanged();
    }