/*
 * Copyright 2014 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



package com.netflix.amazoncomponents.security

import com.amazonaws.AmazonServiceException
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.autoscaling.AmazonAutoScaling
import com.amazonaws.services.autoscaling.model.DescribeAutoScalingGroupsRequest
import com.amazonaws.services.ec2.AmazonEC2
import com.amazonaws.services.ec2.AmazonEC2Client
import com.netflix.amazoncomponents.model.RetryCallback
import org.apache.http.Header
import org.apache.http.HttpEntity
import org.apache.http.HttpResponse
import org.apache.http.StatusLine
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.entity.ContentType
import spock.lang.Specification

class AmazonClientProviderSpec extends Specification {

  void "client proxies to edda when available"() {
    setup:
    def mockHttp = Mock(HttpClient)
    def provider = new AmazonClientProvider(mockHttp)

    when:
    def client = provider.getAutoScaling(new AmazonCredentials(Mock(AWSCredentials), "bar", "edda"), "us-east-1")
    client.describeAutoScalingGroups()

    then:
    client instanceof AmazonAutoScaling
    1 * mockHttp.execute(_) >> {
      mockResponse
    }
  }

  void "edda requests handle parameters from request objects"() {
    setup:
    def asgName = "foo"
    def mockHttp = Mock(HttpClient)
    def provider = new AmazonClientProvider(mockHttp)

    when:
    def client = provider.getAutoScaling(new AmazonCredentials(Mock(AWSCredentials), "bar", "edda"), "us-east-1")
    client.describeAutoScalingGroups()

    then:
    client instanceof AmazonAutoScaling
    1 * mockHttp.execute(_) >> { HttpGet get ->
      assert get.URI.rawPath.endsWith("_expand")
      mockResponse
    }

    when:
    client.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest().withAutoScalingGroupNames(asgName))

    then:
    client instanceof AmazonAutoScaling
    1 * mockHttp.execute(_) >> { HttpGet get ->
      assert get.URI.rawPath.endsWith(asgName)
      getMockResponse(OBJECT_ASG_CONTENT)
    }
  }

  void "client goes directly to amazon when edda is unavailable"() {
    setup:
    def provider = Spy(AmazonClientProvider)
    def ec2 = Mock(AmazonEC2)
    provider.getAmazonEC2(_, _) >> ec2

    when:
    def client = provider.getAmazonEC2(new AmazonCredentials(Mock(AWSCredentials), "bar"), "us-east-1")
    client.describeSecurityGroups()

    then:
    client.is ec2
    1 * ec2.describeSecurityGroups()
  }

  void "unmapped describe calls fall back to aws client"() {
    setup:
    def provider = Spy(AmazonClientProvider)
    def ec2 = Mock(AmazonEC2)
    provider.getAmazonEC2(_, _) >> ec2

    when:
    def client = provider.getAmazonEC2(new AmazonCredentials(Mock(AWSCredentials), "bar", "foo"), "us-east-1")
    client.describeAccountAttributes()

    then:
    client.is ec2
    1 * ec2.describeAccountAttributes()
  }

  void "describe call with no collection ids calls for the full list when edda is configured"() {
    setup:
    def mockHttp = Mock(HttpClient)
    def provider = new AmazonClientProvider(mockHttp)

    when:
    def client = provider.getAutoScaling(new AmazonCredentials(Mock(AWSCredentials), "bar", "edda"), "us-east-1")
    client.describeAutoScalingGroups(new DescribeAutoScalingGroupsRequest())

    then:
    client instanceof AmazonAutoScaling
    1 * mockHttp.execute(_) >> { HttpGet get ->
      assert get.URI.rawPath.endsWith("_expand")
      mockResponse
    }
  }

  void "callback is fired during throttling event"() {
    setup:
    def provider = Spy(AmazonClientProvider)
    def ec2 = Mock(AmazonEC2Client)
    provider.getClient(_, _, _) >> ec2
    def retryCallbackCalled = false
    provider.retryCallback = new RetryCallback() {
      boolean doCall(Throwable t, int attempts) {
        if (attempts < 3) {
          retryCallbackCalled = true
          true
        } else {
          false
        }
      }
    }

    when:
    def client = provider.getAmazonEC2(new AmazonCredentials(Mock(AWSCredentials), "bar", null), "us-east-1")
    client.describeInstances()

    then:
    3 * ec2.describeInstances() >> {
      def ex = new AmazonServiceException("foo")
      ex.errorCode = "RequestLimitExceeded"
      throw ex
    }
    thrown AmazonServiceException
    retryCallbackCalled
  }

  static def OBJECT_ASG_CONTENT = '{ "autoScalingGroupName": "my-app-v000" }'
  static def ARRAY_ASG_CONTENT = "[$OBJECT_ASG_CONTENT]"

  def getMockResponse(String content = ARRAY_ASG_CONTENT) {
    def mock = Mock(HttpResponse)
    def statusLine = Mock(StatusLine)
    statusLine.getStatusCode() >> 200
    mock.getStatusLine() >> statusLine
    def entity = Mock(HttpEntity)
    entity.getContent() >> { new ByteArrayInputStream(content.bytes) }
    def header = Mock(Header)
    header.getValue() >> ContentType.APPLICATION_JSON.getMimeType()
    entity.getContentType() >> header
    mock.getEntity() >> entity
    mock
  }
}
