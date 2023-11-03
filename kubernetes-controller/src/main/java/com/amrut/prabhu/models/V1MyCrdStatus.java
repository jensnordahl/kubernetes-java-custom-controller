/*
 * Kubernetes
 * No description provided (generated by Openapi Generator https://github.com/openapitools/openapi-generator)
 *
 * The version of the OpenAPI document: v1.21.1
 * 
 *
 * NOTE: This class is auto generated by OpenAPI Generator (https://openapi-generator.tech).
 * https://openapi-generator.tech
 * Do not edit the class manually.
 */


package com.amrut.prabhu.models;

import java.util.Objects;
import java.util.Arrays;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import java.io.IOException;

/**
 * V1MyCrdStatus
 */
@javax.annotation.Generated(value = "org.openapitools.codegen.languages.JavaClientCodegen", date = "2023-11-03T12:48:25.326Z[Etc/UTC]")
public class V1MyCrdStatus {
  public static final String SERIALIZED_NAME_CONFIG_MAP_ID = "configMapId";
  @SerializedName(SERIALIZED_NAME_CONFIG_MAP_ID)
  private String configMapId;


  public V1MyCrdStatus configMapId(String configMapId) {
    
    this.configMapId = configMapId;
    return this;
  }

   /**
   * Get configMapId
   * @return configMapId
  **/
  @javax.annotation.Nullable
  @ApiModelProperty(value = "")

  public String getConfigMapId() {
    return configMapId;
  }


  public void setConfigMapId(String configMapId) {
    this.configMapId = configMapId;
  }


  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    V1MyCrdStatus v1MyCrdStatus = (V1MyCrdStatus) o;
    return Objects.equals(this.configMapId, v1MyCrdStatus.configMapId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(configMapId);
  }


  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("class V1MyCrdStatus {\n");
    sb.append("    configMapId: ").append(toIndentedString(configMapId)).append("\n");
    sb.append("}");
    return sb.toString();
  }

  /**
   * Convert the given object to string with each line indented by 4 spaces
   * (except the first line).
   */
  private String toIndentedString(Object o) {
    if (o == null) {
      return "null";
    }
    return o.toString().replace("\n", "\n    ");
  }

}

