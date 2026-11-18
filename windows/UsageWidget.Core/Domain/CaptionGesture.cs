namespace UsageWidget;

public enum CaptionGesture
{
    None,
    Click,
    DragStart,
    Ignore
}

/// <summary>
/// Classifies title-strip mouse sequences: a click under the system drag threshold
/// toggles Always on top; movement past the threshold is a drag and never a click;
/// the second click of a double-click does not toggle again.
/// </summary>
public sealed class CaptionGestureTracker
{
    private bool _armed;
    private bool _dragging;
    private bool _ignoreClick;
    private double _downX;
    private double _downY;
    private double _lastClickX;
    private double _lastClickY;
    private long _lastClickAtMs = long.MinValue;
    private bool _hasLastClick;

    public bool IsArmed => _armed;
    public bool IsDragging => _dragging;

    public static bool ExceedsDragThreshold(double dx, double dy, double thresholdX, double thresholdY) =>
        Math.Abs(dx) >= thresholdX || Math.Abs(dy) >= thresholdY;

    public CaptionGesture Down(
        double x,
        double y,
        long nowMs,
        bool onCaption,
        int clickCount,
        int doubleClickMs,
        double doubleClickSlopX,
        double doubleClickSlopY)
    {
        if (!onCaption)
        {
            _armed = false;
            _dragging = false;
            _ignoreClick = false;
            return CaptionGesture.None;
        }

        _armed = true;
        _dragging = false;
        _downX = x;
        _downY = y;
        _ignoreClick = clickCount >= 2 || IsSecondClickOfDouble(x, y, nowMs, doubleClickMs, doubleClickSlopX, doubleClickSlopY);
        return CaptionGesture.None;
    }

    public CaptionGesture Move(double x, double y, double thresholdX, double thresholdY)
    {
        if (!_armed || _dragging) return CaptionGesture.None;
        if (!ExceedsDragThreshold(x - _downX, y - _downY, thresholdX, thresholdY))
            return CaptionGesture.None;
        _dragging = true;
        _ignoreClick = true;
        return CaptionGesture.DragStart;
    }

    public CaptionGesture Up(double x, double y, long nowMs, double thresholdX, double thresholdY)
    {
        if (!_armed) return CaptionGesture.None;
        _armed = false;
        var dragged = _dragging || ExceedsDragThreshold(x - _downX, y - _downY, thresholdX, thresholdY);
        _dragging = false;
        if (dragged)
        {
            _ignoreClick = false;
            return CaptionGesture.None;
        }
        if (_ignoreClick)
        {
            _ignoreClick = false;
            return CaptionGesture.Ignore;
        }
        _hasLastClick = true;
        _lastClickX = x;
        _lastClickY = y;
        _lastClickAtMs = nowMs;
        return CaptionGesture.Click;
    }

    public void Cancel()
    {
        _armed = false;
        _dragging = false;
        _ignoreClick = false;
    }

    private bool IsSecondClickOfDouble(
        double x,
        double y,
        long nowMs,
        int doubleClickMs,
        double doubleClickSlopX,
        double doubleClickSlopY)
    {
        if (!_hasLastClick) return false;
        if (nowMs - _lastClickAtMs > doubleClickMs) return false;
        return !ExceedsDragThreshold(x - _lastClickX, y - _lastClickY, doubleClickSlopX, doubleClickSlopY);
    }
}
