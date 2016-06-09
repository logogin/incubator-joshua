/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
 package org.apache.joshua.decoder.phrase.decode;

import static com.google.common.base.Charsets.UTF_8;
import static java.nio.file.Files.readAllBytes;
import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.joshua.decoder.Decoder;
import org.apache.joshua.decoder.JoshuaConfiguration;
import org.apache.joshua.decoder.Translation;
import org.apache.joshua.decoder.segment_file.Sentence;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Reimplements the constrained phrase decoding test
 */
@Ignore("re-enable as soon as kenlm native library support will be in place")
public class PhraseDecodingTest {

  private static final String CONFIG = "resources/phrase_decoder/config";
  private static final String INPUT = "una estrategia republicana para obstaculizar la reelección de Obama";
  private static final Path GOLD_PATH = Paths.get("resources/phrase_decoder/output.gold");

  private JoshuaConfiguration joshuaConfig = null;
  private Decoder decoder = null;

  @Before
  public void setUp() throws Exception {
    joshuaConfig = new JoshuaConfiguration();
    joshuaConfig.readConfigFile(CONFIG);
    decoder = new Decoder(joshuaConfig, "");
  }

  @After
  public void tearDown() throws Exception {
    decoder.cleanUp();
    decoder = null;
  }

  @Test
  public void givenInput_whenPhraseDecoding_thenOutputIsAsExpected() throws IOException {
    final String translation = decode(INPUT).toString();
    final String gold = new String(readAllBytes(GOLD_PATH), UTF_8);
    assertEquals(gold, translation);
  }

  private Translation decode(String input) {
    final Sentence sentence = new Sentence(input, 0, joshuaConfig);
    return decoder.decode(sentence);
  }

}
