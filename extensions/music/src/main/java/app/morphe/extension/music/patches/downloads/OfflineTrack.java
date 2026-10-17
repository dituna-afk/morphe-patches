private static final java.util.Map<String, Integer> DOMINANT_COLOR_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public int extractDominantColor(int fallbackColor) {
        Integer cached = DOMINANT_COLOR_CACHE.get(videoId);
        if (cached != null) return cached;

        Bitmap bmp = artwork(64);
        if (bmp == null) return fallbackColor;

        long r = 0, g = 0, b = 0, count = 0;
        int width = bmp.getWidth();
        int height = bmp.getHeight();

        for (int x = 0; x < width; x += 2) {
            for (int y = 0; y < height; y += 2) {
                int pixel = bmp.getPixel(x, y);
                int pr = Color.red(pixel);
                int pg = Color.green(pixel);
                int pb = Color.blue(pixel);
                int brightness = (pr + pg + pb) / 3;
                if (brightness > 20 && brightness < 235) {
                    r += pr;
                    g += pg;
                    b += pb;
                    count++;
                }
            }
        }

        if (count == 0) return fallbackColor;
        int avgR = (int) ((r / count) * 0.45f);
        int avgG = (int) ((g / count) * 0.45f);
        int avgB = (int) ((b / count) * 0.45f);
        int result = Color.rgb(Math.max(18, avgR), Math.max(18, avgG), Math.max(18, avgB));
        DOMINANT_COLOR_CACHE.put(videoId, result);
        return result;
    }