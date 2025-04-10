/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.client.api.endpoint;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import org.apache.solr.client.api.model.CoreStatusResponse;
import org.apache.solr.client.api.model.CreateCoreParams;
import org.apache.solr.client.api.model.CreateCoreResponse;

public interface CoreApis {

  /** V2 API definition for creating a core on the receiving Solr node */
  @Path("/cores")
  interface Create {
    @POST
    @Operation(summary = "Create a new core on the receiving Solr node.", tags = "cores")
    CreateCoreResponse createCore(CreateCoreParams requestBody) throws Exception;
  }

  @Path("/cores")
  interface GetStatus {

    @GET
    @Operation(summary = "Fetch status info for all cores hosted on this node.", tags = "cores")
    CoreStatusResponse getAllCoreStatus(@QueryParam("indexInfo") Boolean indexInfo)
        throws Exception;

    @GET
    @Operation(
        summary = "Fetch status info for the core hosted on this node with the specified name.",
        tags = "cores")
    @Path("/{coreName}")
    CoreStatusResponse getCoreStatus(
        @PathParam("coreName") String coreName, @QueryParam("indexInfo") Boolean indexInfo)
        throws Exception;
  }
}
