import * as cldrListUsers from "../src/esm/cldrListUsers.mjs";

import * as chai from "chai";

export const TestCldrListUsers = "ok";

const assert = chai.assert;

describe("cldrListUsers.ping", function () {
  const result = cldrListUsers.ping();
  it("should return pong", function () {
    assert(result === "pong", "result equals pong");
  });
});
