System Performance Report - Sun Feb  1 12:12:11 IST 2026
-----------------------------------
App Process (com.n8nAndroidServer):
           TOTAL PSS:    77007            TOTAL RSS:   119284       TOTAL SWAP PSS:      282
\nn8n Process (node):
u0_a514       3002 10503 15873268 306464 0                  0 R node
\nCPU Usage (Top 5):
Tasks: 519 total,   3 running, 516 sleeping,   0 stopped,   0 zombie
  Mem:  5542300K total,  5462208K used,    80092K free,    76200K buffers
 Swap:  4194300K total,  2549024K used,  1645276K free,  1346640K cached
800%cpu 252%user   0%nice 245%sys 293%idle   3%iow   0%irq   7%sirq   0%host
  PID USER         PR  NI VIRT  RES  SHR S[%CPU] %MEM     TIME+ ARGS
 3002 u0_a514      20   0  15G 305M  28M R  331   5.6   0:24.05 node /data/user/0/com.n8nAndroidServer/files/runtime/lib/node_modules/n8n/bin/n8n start
 3105 shell        20   0  12G 2.9M 2.0M R 31.0   0.0   0:00.05 top -n 1 -b
  129 root         20   0    0    0    0 S 31.0   0.0  31:25.63 [kswapd0]
10503 shell        20   0  12G  10M 2.2M S 17.2   0.1   0:10.87 adbd --root_seclabel=u:r:su:s0
 1331 bluetooth    20   0  16G  54M  30M S 10.3   0.9  55:42.18 com.android.bluetooth
 1778 u0_a514      20   0  16G 123M  35M S  6.8   2.2   0:03.12 com.n8nAndroidServer
23037 root         RT   0    0    0    0 S  6.8   0.0  46:42.82 [irq/228-1084000]
 1938 root         20   0    0    0    0 I  3.4   0.0   0:00.03 [kworker/2:0]
32587 root         20   0    0    0    0 I  3.4   0.0   0:00.42 [kworker/u16:5]
32220 root         20   0    0    0    0 I  3.4   0.0   0:00.19 [kworker/3:2]
\nNetwork Status (n8n & Gatekeeper):
