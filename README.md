minicluster-hack
================

Hack to get a runner working with Accumulo's mini cluster impl.  Code from 1.6 MiniClusterRunner to work with a copy
of the 1.5 minicluster.  Basically enough work to get it working the way I wanted.


How to use
----------

1. Build
```sh
mvn clean install
```

2. Copy the jar to $ACCUMULO_HOME/lib directory.  This just makes handling the classpath issues much easier and 

3. Run the following command
```sh
$ACCUMULO_HOME/bin/accumulo MiniCluster
```
