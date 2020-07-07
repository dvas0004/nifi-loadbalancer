# NiFi Load Balancer Processor

The purpose of this processor is to allow an administrator to route flow files to multiple downstream destinations,
depending on if a given downstream destination is "alive" or not. The processor accepts dynamic properties which
are used to define a system command line "health check", for example:

`ping -c 1 1.2.3.4`

Assuming the downstream destination is 1.2.3.4, then the above command is run every 5 seconds. As long as the 
command exits with a `0` return code, the destination is considered alive and accepting flow files. Should 1.2.3.4
be unreachable by ping, then the above command would return a non-zero exit code, and the Load Balancing processor
will automatically stop sending flow-files to the destination.

The processor allows for three load-balancing strategies:

- **Round Robin** (default): The processor will send the incoming flow file to the next available destination 
responding to health checks

- **Random**: The processor will send the incoming flow file to a randomly chosen destination responding to 
health checks
 
- **Attribute Hash**: *note - this requires you to set the "Attribute Hash Field" property*. When this strategy is 
chosen, the processor will hash the flow flile attribute specified in the "Attribute Hash Field" property, and 
flow files whose attributes hash to the same value will be sent to the same destination provided said destination
still responds to health checks. This strategy is effective in situations when session "stickiness" is required,
e.g. sending all HTTP requests from a particular user to the same webserver backend

#### Qucikstart Video Demo

- Part 1: https://youtu.be/L7Wpq7nK83M
- Part 2: https://youtu.be/YlwbnB4pvkk

#### Installation

- Download the NAR file https://github.com/dvas0004/nifi-loadbalancer/blob/master/nifi-LoadBalancer-nar/target/nifi-LoadBalancer-nar-1.0-SNAPSHOT.nar
- Move the NAR file to the "lib" directory of your NiFi installation

#### Advanced

The property "Attribute Hash Lifetime" is only used when the loadb alancing strategy is set to "Attribute Hash",
and controls for how long an attribute hash is stored in cache since it was last seen. Setting this to a lower
value may save you some memory, but risks sending a flow file to the wrong destination if the time between
two flow files with the same attribute hash is greater than the lifetime specified.

 
 
