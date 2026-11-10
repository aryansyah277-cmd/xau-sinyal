import lzma, struct, urllib.request, datetime, sys, time
def day(sym, d, scale):
    url = f"https://datafeed.dukascopy.com/datafeed/{sym}/{d.year}/{d.month-1:02d}/{d.day:02d}/BID_candles_min_1.bi5"
    for k in range(4):
        try:
            raw = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "xau-sinyal-backtest"}), timeout=30).read()
            break
        except Exception as e:
            print("retry", url, e, file=sys.stderr); time.sleep(2)
    else:
        return []
    if not raw: return []
    data = lzma.decompress(raw)
    base = int(datetime.datetime(d.year, d.month, d.day, tzinfo=datetime.timezone.utc).timestamp())
    out = []
    for i in range(0, len(data), 24):
        t, o, c, l, h, v = struct.unpack(">iiiiif", data[i:i+24])
        if v == 0 and o == c == l == h: continue
        out.append((base + t, o/scale, h/scale, l/scale, c/scale))
    return out
start = datetime.date(2026, 10, 19); end = datetime.date(2026, 11, 9)
for sym, scale in (("XAUUSD", 1000), ("EURUSD", 100000)):
    rows = []
    d = start
    while d <= end:
        rows += day(sym, d, scale); d += datetime.timedelta(days=1)
    with open(f"{sym}_1m.csv", "w") as f:
        for r in rows: f.write("%d,%.5f,%.5f,%.5f,%.5f\n" % r)
    print(sym, len(rows), rows[:1], rows[-1:])
