/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

/**
 * $Id: $
 */
package com.linkedin.restli.internal.common;

import com.linkedin.data.transform.filter.FilterConstants;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import com.linkedin.data.DataMap;
import com.linkedin.data.transform.filter.request.MaskOperation;
import com.linkedin.data.transform.filter.request.MaskTree;

/**
 * Class with implementation of helper methods to serialize/deserialize mask to/from URI
 * parameter.
 *
 * @author Josh Walker
 * @author jodzga
 */
public class URIMaskUtil
{

  /**
   * Generate a serialized string for the input {@link MaskTree}. The returned string is not URL encoded and must be
   * encoded elsewhere before using this in the request URI.
   *
   * @param maskTree the {@link MaskTree} to serialize
   * @return a String
   */
  public static String encodeMaskForURI(MaskTree maskTree)
  {
    return URIMaskUtil.encodeMaskForURIImpl(maskTree.getDataMap(), false);
  }

  /**
   * Generate a serialized string for the input {@link MaskTree}. The returned string is not URL encoded and must be
   * encoded elsewhere before using this in the request URI.
   *
   * @param simplifiedMask {@link DataMap} representation of the mask to serialize
   * @return a String
   */
  public static String encodeMaskForURI(DataMap simplifiedMask)
  {
    return URIMaskUtil.encodeMaskForURIImpl(simplifiedMask, false);
  }

  private static String encodeMaskForURIImpl(DataMap simplifiedMask, boolean parenthesize)
  {
    StringBuilder result = new StringBuilder();
    if (parenthesize)
    {
      result.append(":(");
    }
    boolean delimit = false;
    for (Map.Entry<String, Object> entry : simplifiedMask.entrySet())
    {
      if (delimit)
      {
        result.append(",");
      }
      delimit = true;

      if ((FilterConstants.START.equals(entry.getKey()) || FilterConstants.COUNT.equals(entry.getKey())) &&
          entry.getValue() instanceof Integer)
      {
        result.append(entry.getKey());
        result.append(":").append(entry.getValue());
      }
      else if (entry.getValue().equals(MaskOperation.POSITIVE_MASK_OP.getRepresentation()))
      {
        result.append(entry.getKey());
      }
      else if (entry.getValue()
                    .equals(MaskOperation.NEGATIVE_MASK_OP.getRepresentation()))
      {
        result.append('-');
        result.append(entry.getKey());
      }
      else
      {
        result.append(entry.getKey());
        result.append(encodeMaskForURIImpl((DataMap) entry.getValue(), true));
      }
    }
    if (parenthesize)
    {
      result.append(")");
    }

    return result.toString();
  }

  /**
   * Return a {@link MaskTree} that is deserialized from the input projection mask string used in URI parameter. The
   * input projection string must have been URL decoded if the projection was part of a request URI.
   *
   * @param toparse StringBuilder containing a string representation of an encoded MaskTree
   * @return a MaskTree
   * @throws IllegalMaskException if syntax in the input is malformed
   */
  public static MaskTree decodeMaskUriFormat(StringBuilder toparse) throws IllegalMaskException
  {
    ParseState state = ParseState.PARSE_FIELDS;

    DataMap result = new DataMap();
    Deque<DataMap> stack = new ArrayDeque<DataMap>();
    stack.addLast(result);

    while (toparse.length() > 0)
    {
      switch (state)
      {
      case TRAVERSE:
        if (toparse.indexOf(",") != 0)
        {
          throw new IllegalStateException("Internal Error parsing mask: unexpected parse buffer '"
              + toparse + "' while traversing");
        }
        toparse.delete(0, 1);
        state = ParseState.PARSE_FIELDS;
        break;
      case DESCEND:
        if (toparse.indexOf(":(") != 0)
        {
          throw new IllegalStateException("Internal Error parsing mask: unexpected parse buffer '"
              + toparse + "' while descending");
        }
        toparse.delete(0, 2);
        state = ParseState.PARSE_FIELDS;
        break;
      case PARSE_FIELDS:

        Integer maskValue = null;
        if (toparse.charAt(0) == '-')
        {
          maskValue = MaskOperation.NEGATIVE_MASK_OP.getRepresentation();
          toparse.delete(0, 1);
        }
        else
        {
          maskValue = MaskOperation.POSITIVE_MASK_OP.getRepresentation();
        }

        int nextToken = -1;
        StringBuilder field = new StringBuilder();
        for (int ii = 0; ii < toparse.length(); ++ii)
        {
          char c = toparse.charAt(ii);
          switch (c)
          {
          case ',':
            state = ParseState.TRAVERSE;
            nextToken = ii;
            break;
          case ':':
            if (field.length() > 0 && (FilterConstants.START.equals(field.toString()) || FilterConstants.COUNT.equals(field.toString())))
            {
              if (!Character.isDigit(toparse.charAt(ii + 1)))
              {
                throw new IllegalMaskException("Malformed mask syntax: unexpected range value");
              }

              ii++;

              // Aggressively consume the numerical value for the range parameter as this is a special case.
              StringBuilder rangeValue = new StringBuilder();
              while (ii < toparse.length())
              {
                char ch = toparse.charAt(ii);
                if (ch == ',')
                {
                  state = ParseState.TRAVERSE;
                  nextToken = ii;
                  break;
                }
                else if (ch == ')')
                {
                  state = ParseState.ASCEND;
                  nextToken = ii;
                  break;
                }
                else if (Character.isDigit(ch))
                {
                  rangeValue.append(ch);
                }
                else
                {
                  throw new IllegalMaskException("Malformed mask syntax: unexpected range value");
                }
                ii++;
              }

              // Set the mask value to the range value specified for the parameter
              maskValue = Integer.valueOf(rangeValue.toString());
            }
            else
            {
              if (toparse.charAt(ii + 1) != '(')
              {
                throw new IllegalMaskException("Malformed mask syntax: expected '(' token");
              }

              state = ParseState.DESCEND;
              nextToken = ii;
            }
            break;
          case ')':
            state = ParseState.ASCEND;
            nextToken = ii;
            break;
          default:
            field.append(c);
            break;
          }
          if (nextToken != -1)
          {
            break;
          }
        }
        if (toparse.length() != field.length())
        {
          if (nextToken == -1)
          {
            throw new IllegalMaskException("Malformed mask syntax: expected closing token");
          }
          toparse.delete(0, nextToken);
        }
        else
        {
          toparse.delete(0, toparse.length());
        }
        if (state == ParseState.DESCEND)
        {
          if (field.length() == 0)
          {
            throw new IllegalMaskException("Malformed mask syntax: empty parent field name");
          }
          DataMap subTree = new DataMap();
          stack.peekLast().put(field.toString().trim(), subTree);
          stack.addLast(subTree);
        }
        else if (field.length() != 0)
        {
          stack.peekLast().put(field.toString().trim(), maskValue);
        }
        break;
      case ASCEND:
        if (toparse.indexOf(")") != 0)
        {
          throw new IllegalStateException("Internal Error parsing mask: unexpected parse buffer '"
              + toparse + "' while ascending");
        }
        if (stack.isEmpty())
        {
          throw new IllegalMaskException("Malformed mask syntax: unexpected ')' token");
        }
        toparse.delete(0, 1);
        stack.removeLast();
        state = ParseState.PARSE_FIELDS;
        break;
      }
    }
    if (stack.size() != 1)
    {
      throw new IllegalMaskException("Malformed mask syntax: unmatched nesting");
    }
    result = stack.removeLast();
    return new MaskTree(result);
  }

  private enum ParseState
  {
    DESCEND, PARSE_FIELDS, ASCEND, TRAVERSE
  }

}
