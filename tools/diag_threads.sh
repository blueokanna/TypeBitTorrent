#!/system/bin/sh
# Diagnose typebit-engine threads of a given pid
PID=$1
if [ -z "$PID" ]; then
  PID=$(pidof com.typebit.app)
fi
echo "PID=$PID"
TOTAL=0
for t in /proc/$PID/task/*; do
  c=$(cat $t/comm 2>/dev/null)
  if [ "$c" = "typebit-engine" ]; then
    TOTAL=$((TOTAL+1))
    st=$(awk '{print $3}' $t/stat 2>/dev/null)
    # state + wchan (what the thread is blocked on, if sleeping)
    wc=$(cat $t/wchan 2>/dev/null)
    echo "engine thread state=$st wchan=$wc"
  fi
done
echo "TOTAL engine threads=$TOTAL"
