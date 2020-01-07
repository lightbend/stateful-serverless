/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
const path = require("path");
const grpc = require("grpc");
const protoLoader = require("@grpc/proto-loader");
const debug = require("debug")("cloudstate-event-sourcing");
// Bind to stdout
debug.log = console.log.bind(console);
const AnySupport = require("./protobuf-any");

class StatelessSupport {

  constructor(root, service, handlers, allEntities) {
    this.root = root;
    this.service = service;    
    this.anySupport = new AnySupport(this.root);
    this.anySupport = new AnySupport(this.root);
    this.commandHandlers = handlers.commandHandlers;
    this.allEntities = allEntities;
  }

  serialize(obj, requireJsonType) {
    //return AnySupport.serialize(obj, this.options.serializeAllowPrimitives, this.options.serializeFallbackToJson, requireJsonType);
    return AnySupport.serialize(obj, true, false, requireJsonType);
  }

  deserialize(any) {
    return this.anySupport.deserialize(any);
  }

}



module.exports = class StatelessServices {

  constructor() {
    this.services = {};
  }

  addService(entity, allEntities) {
    this.services[entity.serviceName] = new StatelessSupport(entity.root, entity.service, {
      commandHandlers: entity.commandHandlers      
    }, allEntities);
  }

  entityType() {
    return "cloudstate.function.StatelessFunction";
  }

  register(server) {
    const includeDirs = [
      path.join(__dirname, "..", "proto"),
      path.join(__dirname, "..", "protoc", "include")
    ];
    const packageDefinition = protoLoader.loadSync(path.join("cloudstate", "function.proto"), {
      includeDirs: includeDirs
    });
    const grpcDescriptor = grpc.loadPackageDefinition(packageDefinition);

    const statelessService = grpcDescriptor.cloudstate.function.StatelessFunction.service;

    server.addService(statelessService, {
      handleUnary: this.handleUnary.bind(this),
      handleStreamedIn: this.handleStreamedIn.bind(this),
      handleStreamedOut: this.handleStreamedOut.bind(this),
      handleStreamed: this.handleStreamed.bind(this),
    });    
  }

  handleStreamed(call){
    call.on("data", data => {
      if (this.services[data.serviceName] && this.services[data.serviceName].commandHandlers.hasOwnProperty(data.name)) {
        const userStream = {
          write: (userData) => {
            const grpcReturn = this.services[data.serviceName].service.methods[data.name].resolvedResponseType.fromObject(userData);    
            const requireJsonType =true;
            call.write({        
              reply:{
                payload: AnySupport.serialize(grpcReturn, false, false, requireJsonType)          
              }        
            });
          },
          end: () => call.end(),                 
        }
        // We call this every time and send a way to stream back .. not sure if this is a good way to do things?
        this.services[data.serviceName].commandHandlers[data.name](userStream, this.services[data.serviceName].deserialize(data.payload)); 
      }else{
        console.warn("There is no user function with name: " + data.serviceName + "." + data.name);        
      }
    });
    call.on("end", () => {
      console.debug("stream ended")
      //call.end();      
    });
  }

  handleStreamedOut(call){
    const data = call.request;
    if (this.services[data.serviceName] && this.services[data.serviceName].commandHandlers.hasOwnProperty(data.name)) {
      const userStream = {
        write: (userData) => {
          const grpcReturn = this.services[data.serviceName].service.methods[data.name].resolvedResponseType.fromObject(userData);    
          const requireJsonType =true;
          call.write({        
            reply:{
              payload: AnySupport.serialize(grpcReturn, false, false, requireJsonType)          
            }        
          });
        },
        end: () => call.end()        
      }
      this.services[data.serviceName].commandHandlers[data.name](userStream, this.services[data.serviceName].deserialize(data.payload));            
    }else{
      console.warn("There is no user function with name: ", this.services[data.serviceName] + "." + data.name);      
    }    
  }

  handleStreamedIn(call, callback){
    call.on("data", data => {
      if (this.services[data.serviceName] && this.services[data.serviceName].commandHandlers.hasOwnProperty(data.name)) {
        const userReturn = this.services[data.serviceName].commandHandlers[data.name](this.services[data.serviceName].deserialize(data.payload));
        const grpcReturn = this.services[data.serviceName].service.methods[data.name].resolvedResponseType.fromObject(userReturn);
        const requireJsonType =true;
        callback(null, {        
          reply:{
            payload: AnySupport.serialize(grpcReturn, false, false, requireJsonType)          
          }        
        });
      }else{
        console.warn("There is no user function with name: " + call.request.serviceName);
        callback();
      }
    });
    call.on("end", () => {
      console.debug("stream ended")
      //call.end();      
    });

  }

  handleUnary(call, callback){
    if (this.services[call.request.serviceName] && this.services[call.request.serviceName].commandHandlers.hasOwnProperty(call.request.name)) {
      const userReturn = this.services[call.request.serviceName].commandHandlers[call.request.name](this.services[call.request.serviceName].deserialize(call.request.payload));
      const grpcReturn = this.services[call.request.serviceName].service.methods[call.request.name].resolvedResponseType.fromObject(userReturn);
      const requireJsonType =true;
      var metadata = new grpc.Metadata();
      callback(null, {        
        reply:{
          payload: AnySupport.serialize(grpcReturn, false, false, requireJsonType)          
        }        
      }, metadata);
    }else{
      console.warn("There is no user function with name: " + call.request.serviceName);
      callback();
    }    
  }
};
