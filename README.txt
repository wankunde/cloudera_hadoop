For the latest information about Hadoop, please visit our website at:

   http://hadoop.apache.org/core/

and our wiki, at:

   http://wiki.apache.org/hadoop/

This distribution includes cryptographic software.  The country in 
which you currently reside may have restrictions on the import, 
possession, use, and/or re-export to another country, of 
encryption software.  BEFORE using any encryption software, please 
check your country's laws, regulations and policies concerning the
import, possession, or use, and re-export of encryption software, to 
see if this is permitted.  See <http://www.wassenaar.org/> for more
information.

The U.S. Government Department of Commerce, Bureau of Industry and
Security (BIS), has classified this software as Export Commodity 
Control Number (ECCN) 5D002.C.1, which includes information security
software using or performing cryptographic functions with asymmetric
algorithms.  The form and manner of this Apache Software Foundation
distribution makes it eligible for export under the License Exception
ENC Technology Software Unrestricted (TSU) exception (see the BIS 
Export Administration Regulations, Section 740.13) for both object 
code and source code.

The following provides more details on the included cryptographic
software:
  Hadoop Core uses the SSL libraries from the Jetty project written 
by mortbay.org.

# Profiling Applications Using YourKit

[YourKit downloads page](https://www.yourkit.com/java/profiler/download/)
Here are instructions on profiling applications using YourKit Java Profiler.

* After logging into the master node, download the YourKit Java Profiler for Linux from the YourKit downloads page. This file is pretty big (~100 MB) and YourKit downloads site is somewhat slow, so you may consider mirroring this file or including it on a custom AMI.
* Unzip this file somewhere (in /root in our case): unzip YourKit-JavaProfiler-2017.02-b66.zip
* Copy the expanded YourKit files to each node using copy-dir: ~/copy-dir /root/YourKit-JavaProfiler-2017.02
* Configure the HADOOP JVMs to use the YourKit profiling agent by editing ${HADOOP_HOME}/conf/hadoop-env.sh and adding the lines

```
export HADOOP_NAMENODE_OPTS=" -agentpath:${YourKit_PATH}/bin/linux-x86-64/libyjpagent.so ${HADOOP_NAMENODE_OPTS}"
export HADOOP_DATANODE_OPTS=" -agentpath:${YourKit_PATH}/bin/linux-x86-64/libyjpagent.so ${HADOOP_DATANODE_OPTS}"
export YARN_RESOURCEMANAGER_OPTS=" -agentpath:${YourKit_PATH}/bin/linux-x86-64/libyjpagent.so ${YARN_RESOURCEMANAGER_OPTS}"
export YARN_NODEMANAGER_OPTS=" -agentpath:${YourKit_PATH}/bin/linux-x86-64/libyjpagent.so ${YARN_NODEMANAGER_OPTS}"
```

* By default, the YourKit profiler agents use ports 10001-10010. To connect the YourKit desktop application to the remote profiler agents.
* Launch the YourKit profiler on your desktop.
* Select “Connect to remote application…” from the welcome screen and enter the the address of your worker machine.
* YourKit should now be connected to the remote profiling agent. It may take a few moments for profiling information to appear.
