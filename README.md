Guardian System's Riemann Config
================================

This repo contains the current version of the Riemann configuration run on the
main monitoring servers.

The main tasks we use Riemann for are:
 * Receiving metrics from ganglia and other metric systems
 * Thresholding metrics and attaching state and description metadata
 * Carrying out ratio calculations and other simple computations
 * Roll-up (based on changed state etc)
 * Forwarding events into alerta (the central monitoring dashboard)

Making changes
--------------

If you want to add your own metric processing (currently only from ganglia 
then you are welcome to make a pull request.  The configuration is written in
Clojure.  We suggest you look at what is there and copy and paste an 
appropriate rule.  If there is not an existing piece of config that does what
you need then please come and talk to us.

Either way, once you are done, submit a pull request to the master branch. Once
merged in it will get deployed to the monitoring servers.

Testing changes
---------------

The main entry point is main.clj and this omits initialising the logging so
that it can be specified on the server.

The local-testing.config can be used for locally testing changes before 
submitting, setting up a log file in your working directory.  Simply run
Riemann with the local-testing.config as the command line parameter.