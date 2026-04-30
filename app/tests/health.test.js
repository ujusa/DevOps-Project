const request = require("supertest");
const app = require("../src/server");

describe("Health endpoint", () => {
  it("should return healthy status", async () => {
    const response = await request(app).get("/health");

    expect(response.statusCode).toBe(200);
    expect(response.body.status).toBe("healthy");
  });
});